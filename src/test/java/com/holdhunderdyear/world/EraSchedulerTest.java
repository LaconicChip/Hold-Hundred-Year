package com.holdhunderdyear.world;

import com.holdhunderdyear.game.constants.EraDefs;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.enums.EraCode;
import com.holdhunderdyear.world.EraScheduler.EraInstance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EraSchedulerTest {

    private static final long SEED = 42L;

    private final EraScheduler scheduler = new EraScheduler();

    @Test
    void rollsAllElevenErasWithConsecutiveIndices() {
        List<EraInstance> eras = scheduler.roll(SEED);
        assertEquals(11, eras.size());
        for (int i = 0; i < 11; i++) {
            assertEquals(i + 1, eras.get(i).eraIndex(), "eraIndex at position " + i);
            assertEquals(EraCode.values()[i], eras.get(i).code(), "eraCode at position " + i);
        }
    }

    @Test
    void boundariesNeitherOverlapNorLeaveGaps() {
        for (long seed = 1; seed <= 1000; seed++) {
            List<EraInstance> eras = scheduler.roll(seed);
            assertEquals(1, eras.get(0).startDay(), "seed " + seed + " first era must start at day 1");
            assertEquals(108, eras.get(10).endDay(), "seed " + seed + " last era must end at day 108");
            for (int i = 0; i < 10; i++) {
                assertEquals(eras.get(i).endDay() + 1, eras.get(i + 1).startDay(),
                        "seed " + seed + " gap/overlap between era " + (i + 1) + " and " + (i + 2));
            }
        }
    }

    @Test
    void everyEraLastsAtLeastThreeDaysAndStaysWithinItsYear() {
        int[] yearStart = {1, 1, 1, 1, 37, 37, 37, 73, 73, 73, 73};
        int[] yearEnd = {36, 36, 36, 36, 72, 72, 72, 108, 108, 108, 108};
        for (long seed = 1; seed <= 1000; seed++) {
            List<EraInstance> eras = scheduler.roll(seed);
            for (int i = 0; i < 11; i++) {
                EraInstance era = eras.get(i);
                int len = era.endDay() - era.startDay() + 1;
                assertTrue(len >= 3, "seed " + seed + " era " + (i + 1) + " length " + len + " < 3");
                assertTrue(era.startDay() >= yearStart[i] && era.endDay() <= yearEnd[i],
                        "seed " + seed + " era " + (i + 1) + " crosses year: ["
                                + era.startDay() + "," + era.endDay() + "]");
            }
        }
    }

    @Test
    void circuitBreakerStartsAtDay37WithLengthThreeToFive() {
        for (long seed = 1; seed <= 1000; seed++) {
            EraInstance era5 = scheduler.roll(seed).get(4);
            assertEquals(EraCode.CIRCUIT_BREAKER, era5.code());
            assertEquals(37, era5.startDay(), "seed " + seed + " era 5 startDay");
            int len = era5.endDay() - era5.startDay() + 1;
            assertTrue(len >= 3 && len <= 5, "seed " + seed + " era 5 length " + len);
        }
    }

    @Test
    void hundredSeedsYieldMostlyDistinctSchedules() {
        Set<List<EraInstance>> distinct = LongStream.rangeClosed(1, 100)
                .mapToObj(scheduler::roll)
                .collect(Collectors.toSet());
        assertTrue(distinct.size() >= 90,
                "only " + distinct.size() + " distinct schedules out of 100 seeds");
    }

    @Test
    void paramsJsonValuesStayWithinDeclaredRanges() {
        JsonMapper mapper = JsonMapper.builder().build();
        for (long seed : new long[]{SEED, 7L, 20240101L}) {
            List<EraInstance> eras = scheduler.roll(seed);
            for (int i = 0; i < 11; i++) {
                EraInstance era = eras.get(i);
                EraDefs.EraDef def = EraDefs.ALL.get(i);

                JsonNode params = mapper.readTree(era.paramsJson());
                assertTrue(params.has("muMkt"), "seed " + seed + " era " + (i + 1) + " paramsJson misses muMkt");
                assertTrue(params.has("sectorCoefs"), "seed " + seed + " era " + (i + 1) + " paramsJson misses sectorCoefs");

                double muMkt = params.get("muMkt").asDouble();
                assertTrue(muMkt >= def.muMin() && muMkt <= def.muMax(),
                        "seed " + seed + " era " + (i + 1) + " muMkt " + muMkt
                                + " outside [" + def.muMin() + "," + def.muMax() + "]");

                JsonNode sectorCoefs = params.get("sectorCoefs");
                Set<String> actualKeys = new HashSet<>();
                for (Map.Entry<String, JsonNode> e : sectorCoefs.properties()) {
                    actualKeys.add(e.getKey());
                }
                assertEquals(def.sectorRanges().keySet(), actualKeys,
                        "seed " + seed + " era " + (i + 1) + " sectorCoefs keys mismatch");
                for (Map.Entry<String, EraDefs.SectorRange> e : def.sectorRanges().entrySet()) {
                    double coef = sectorCoefs.get(e.getKey()).asDouble();
                    assertTrue(coef >= e.getValue().min() && coef <= e.getValue().max(),
                            "seed " + seed + " era " + (i + 1) + " sector " + e.getKey()
                                    + " coef " + coef + " outside [" + e.getValue().min()
                                    + "," + e.getValue().max() + "]");
                }
            }
        }
    }

    @Test
    void sameSeedRollsExactSameSchedule() {
        for (long seed : new long[]{SEED, 7L, 999L}) {
            List<EraInstance> first = scheduler.roll(seed);
            List<EraInstance> second = scheduler.roll(seed);
            assertEquals(first, second, "seed " + seed + " must roll identical schedules");
            for (int i = 0; i < 11; i++) {
                assertEquals(first.get(i).paramsJson(), second.get(i).paramsJson(),
                        "seed " + seed + " era " + (i + 1) + " paramsJson must be reproducible");
            }
        }
    }

    @Test
    void rolledSectorKeysAllBelongToStockDefs() {
        Set<String> knownSectors = StockDefs.ALL.stream()
                .map(StockDefs.StockDef::sector)
                .collect(Collectors.toSet());
        assertEquals(21, knownSectors.size());
        for (long seed = 1; seed <= 50; seed++) {
            for (EraInstance era : scheduler.roll(seed)) {
                for (String sector : era.sectorCoefs().keySet()) {
                    assertTrue(knownSectors.contains(sector), "unknown sector key: " + sector);
                }
            }
        }
    }
}
