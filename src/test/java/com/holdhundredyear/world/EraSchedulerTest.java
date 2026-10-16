package com.holdhundredyear.world;

import com.holdhundredyear.game.constants.EraDefs;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.enums.EraCode;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.world.EraScheduler.EraInstance;
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
    void eraDisplayNamesMatchSpecExactly() {
        String[] expectedNames = {"牛市启动", "全民疯牛", "股灾降临", "灾后震荡", "熔断开局",
                "蓝筹白马慢牛", "贸易战熊市", "新能源结构牛", "AI疯牛", "黑7月", "震荡再平衡"};
        EraCode[] codes = EraCode.values();
        assertEquals(expectedNames.length, codes.length);
        for (int i = 0; i < codes.length; i++) {
            assertEquals(expectedNames[i], codes[i].getName(), "display name of " + codes[i]);
        }
    }

    @Test
    void boundariesNeitherOverlapNorLeaveGaps() {
        for (long seed = 1; seed <= 1000; seed++) {
            List<EraInstance> eras = scheduler.roll(seed);
            assertEquals(1, eras.get(0).startDay(), "seed " + seed + " first era must start at day 1");
            assertEquals(GameMode.STANDARD.totalDays(), eras.get(10).endDay(),
                "seed " + seed + " last era must end on the final trading day");
            for (int i = 0; i < 10; i++) {
                assertEquals(eras.get(i).endDay() + 1, eras.get(i + 1).startDay(),
                        "seed " + seed + " gap/overlap between era " + (i + 1) + " and " + (i + 2));
            }
        }
    }

    /**
     * 纪元 3（股灾降临）要装下"≥2 天千股跌停 → 救市 → 次日千股涨停"，任何模式下都不得短于 4 天：
     * 快速模式把年长砍到 12 天，若放任抖动压成 2~3 天，股灾会演成一日游、涨停还会漏到下一个纪元。
     */
    @Test
    void eraThreeKeepsRoomForTheCrashChain() {
        for (GameMode mode : GameMode.values()) {
            for (long seed = 1; seed <= 1000; seed++) {
                EraInstance era3 = scheduler.roll(seed, mode).get(2);
                int len = era3.endDay() - era3.startDay() + 1;
                assertTrue(len >= 4, "mode " + mode + " seed " + seed + " era3 length " + len + " < 4");
            }
        }
    }

    @Test
    void everyEraLastsAtLeastThreeDaysAndStaysWithinItsYear() {
        // 纪元 1-4 属年1、5-7 属年2、8-11 属年3；年长由 GameMode.STANDARD.getDaysPerYear() 决定
        int[] eraYear = {0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2};
        int dyp = GameMode.STANDARD.getDaysPerYear();
        for (long seed = 1; seed <= 1000; seed++) {
            List<EraInstance> eras = scheduler.roll(seed);
            for (int i = 0; i < 11; i++) {
                EraInstance era = eras.get(i);
                int len = era.endDay() - era.startDay() + 1;
                assertTrue(len >= 3, "seed " + seed + " era " + (i + 1) + " length " + len + " < 3");
                assertTrue(era.startDay() >= dyp * eraYear[i] + 1 && era.endDay() <= dyp * (eraYear[i] + 1),
                        "seed " + seed + " era " + (i + 1) + " crosses year " + (eraYear[i] + 1) + ": ["
                                + era.startDay() + "," + era.endDay() + "]");
            }
        }
    }

    @Test
    void circuitBreakerEraOpensYearTwoWithLengthThreeToFive() {
        for (long seed = 1; seed <= 1000; seed++) {
            EraInstance era5 = scheduler.roll(seed).get(4);
            assertEquals(EraCode.CIRCUIT_BREAKER, era5.code());
            assertEquals(GameMode.STANDARD.getDaysPerYear() + 1, era5.startDay(),
                    "seed " + seed + " era 5 must open year 2");
            int len = era5.endDay() - era5.startDay() + 1;
            assertTrue(len >= 3 && len <= 5, "seed " + seed + " era 5 length " + len);
        }
    }

    @Test
    void quickModeStillCoversAllElevenErasInsideThirtySixDays() {
        for (long seed = 1; seed <= 1000; seed++) {
            List<EraInstance> eras = scheduler.roll(seed, GameMode.QUICK);
            assertEquals(11, eras.size(), "seed " + seed + " quick mode must keep all 11 eras");
            assertEquals(1, eras.get(0).startDay(), "seed " + seed + " first era starts day 1");
            assertEquals(GameMode.QUICK.totalDays(), eras.get(10).endDay(),
                    "seed " + seed + " last era must end on the quick-mode final day");
            for (int i = 0; i < 11; i++) {
                EraInstance era = eras.get(i);
                int len = era.endDay() - era.startDay() + 1;
                assertTrue(len >= GameMode.QUICK.getMinEraLen(),
                        "seed " + seed + " quick era " + (i + 1) + " length " + len
                                + " < " + GameMode.QUICK.getMinEraLen());
                assertTrue(era.startDay() >= 1 && era.endDay() <= 36,
                        "seed " + seed + " quick era " + (i + 1) + " outside 1..36");
                if (i < 10) {
                    assertEquals(era.endDay() + 1, eras.get(i + 1).startDay(),
                            "seed " + seed + " gap/overlap between quick era " + (i + 1) + " and " + (i + 2));
                }
            }
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

                // 档案里的 mu/sector 是相对强度，落地统一乘 ERA_DRIFT_SCALE 标定（见 GameConstants），
                // 所以这里断言的是"缩放后的区间"，别改回原值：那等于把标定偷偷取消。
                double scale = GameConstants.ERA_DRIFT_SCALE;
                double muMkt = params.get("muMkt").asDouble();
                assertTrue(muMkt >= def.muMin() * scale && muMkt <= def.muMax() * scale,
                        "seed " + seed + " era " + (i + 1) + " muMkt " + muMkt
                                + " outside [" + def.muMin() * scale + "," + def.muMax() * scale + "]");

                JsonNode sectorCoefs = params.get("sectorCoefs");
                Set<String> actualKeys = new HashSet<>();
                for (Map.Entry<String, JsonNode> e : sectorCoefs.properties()) {
                    actualKeys.add(e.getKey());
                }
                assertEquals(def.sectorRanges().keySet(), actualKeys,
                        "seed " + seed + " era " + (i + 1) + " sectorCoefs keys mismatch");
                for (Map.Entry<String, EraDefs.SectorRange> e : def.sectorRanges().entrySet()) {
                    double coef = sectorCoefs.get(e.getKey()).asDouble();
                    assertTrue(coef >= e.getValue().min() * scale && coef <= e.getValue().max() * scale,
                            "seed " + seed + " era " + (i + 1) + " sector " + e.getKey()
                                    + " coef " + coef + " outside [" + e.getValue().min() * scale
                                    + "," + e.getValue().max() * scale + "]");
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
