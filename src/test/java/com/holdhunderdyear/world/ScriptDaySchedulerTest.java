package com.holdhunderdyear.world;

import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.enums.ScriptType;
import com.holdhunderdyear.world.EraScheduler.EraInstance;
import com.holdhunderdyear.world.ScriptDayScheduler.ScriptDayInstance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptDaySchedulerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final int MAX_SEED = 200;

    private static final Set<Long> TECH_STOCK_IDS = StockDefs.ALL.stream()
            .filter(s -> Set.of("半导体", "算力", "通信", "传媒").contains(s.sector()))
            .map(StockDefs.StockDef::id)
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> IPO_NAME_POOL = Set.of(
            "雷霆安防", "银河芯动", "九霄云算", "聚宝盆金服", "盘古智驾", "凤凰光学",
            "鲸落生物", "长城数科", "天权航天", "锦鲤锂业", "貔貅支付", "玄武云储");

    private final EraScheduler eraScheduler = new EraScheduler();
    private final ScriptDayScheduler scheduler = new ScriptDayScheduler();

    @Test
    void scriptTypeValuesAndDisplayNamesMatchSpecExactly() {
        String[] expectedNames = {"千股涨停", "千股跌停", "救市日", "熔断日", "制裁闪崩", "黑7月崩盘", "新股申购"};
        ScriptType[] types = ScriptType.values();
        assertEquals(7, types.length);
        for (int i = 0; i < types.length; i++) {
            assertEquals(expectedNames[i], types[i].getName(), "display name of " + types[i]);
        }
        assertEquals("LIMIT_UP_ALL", types[0].name());
        assertEquals("LIMIT_DOWN_ALL", types[1].name());
        assertEquals("RESCUE", types[2].name());
        assertEquals("CIRCUIT_BREAKER", types[3].name());
        assertEquals("SANCTION", types[4].name());
        assertEquals("CRASH_WEEK", types[5].name());
        assertEquals("IPO", types[6].name());
    }

    @Test
    void techStockPoolDerivesFromStockDefs() {
        assertEquals(Set.of(2L, 14L, 15L, 22L, 24L), TECH_STOCK_IDS);
    }

    @Test
    void limitDownAllOccursTwoToThreeTimesStartingAtEra3Head() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed);
            EraInstance era3 = eras.get(2);
            List<ScriptDayInstance> downDays = ofType(scheduler.roll(seed, eras), ScriptType.LIMIT_DOWN_ALL);
            assertTrue(downDays.size() >= 2 && downDays.size() <= 3,
                    "seed " + seed + " LIMIT_DOWN_ALL count " + downDays.size());
            int first = downDays.get(0).gameDay();
            assertTrue(first == era3.startDay() || first == era3.startDay() + 1,
                    "seed " + seed + " first LIMIT_DOWN_ALL " + first
                            + " not within era3 head [" + era3.startDay() + "," + (era3.startDay() + 1) + "]");
            for (ScriptDayInstance d : downDays) {
                assertTrue(d.gameDay() >= era3.startDay() && d.gameDay() <= era3.endDay(),
                        "seed " + seed + " LIMIT_DOWN_ALL day " + d.gameDay() + " outside era3");
            }
        }
    }

    @Test
    void rescueHappensExactlyOnceInEra3SecondHalf() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed);
            EraInstance era3 = eras.get(2);
            List<ScriptDayInstance> rescues = ofType(scheduler.roll(seed, eras), ScriptType.RESCUE);
            assertEquals(1, rescues.size(), "seed " + seed + " RESCUE count");
            int len = era3.endDay() - era3.startDay() + 1;
            int secondHalfStart = era3.startDay() + (len + 1) / 2;
            int rescueDay = rescues.get(0).gameDay();
            assertTrue(rescueDay >= secondHalfStart && rescueDay <= era3.endDay(),
                    "seed " + seed + " rescueDay " + rescueDay
                            + " outside era3 second half [" + secondHalfStart + "," + era3.endDay() + "]");
            assertTrue(rescues.get(0).params().isEmpty(), "RESCUE params must be empty");
        }
    }

    @Test
    void limitUpAllCoversRescueNextDayPlusOneOrTwoInEra2() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed);
            EraInstance era2 = eras.get(1);
            List<ScriptDayInstance> days = scheduler.roll(seed, eras);
            int rescueDay = ofType(days, ScriptType.RESCUE).get(0).gameDay();
            List<ScriptDayInstance> upDays = ofType(days, ScriptType.LIMIT_UP_ALL);
            long onRescueNext = upDays.stream().filter(d -> d.gameDay() == rescueDay + 1).count();
            assertEquals(1, onRescueNext, "seed " + seed + " LIMIT_UP_ALL on rescueDay+1");
            long inEra2 = upDays.stream()
                    .filter(d -> d.gameDay() >= era2.startDay() && d.gameDay() <= era2.endDay())
                    .count();
            assertTrue(inEra2 >= 1 && inEra2 <= 2, "seed " + seed + " LIMIT_UP_ALL in era2 count " + inEra2);
            assertEquals(inEra2 + 1, upDays.size(), "seed " + seed + " LIMIT_UP_ALL total");
        }
    }

    @Test
    void circuitBreakerOccupiesFirstTwoTradingDaysOfEra5() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed);
            EraInstance era5 = eras.get(4);
            List<ScriptDayInstance> breakers = ofType(scheduler.roll(seed, eras), ScriptType.CIRCUIT_BREAKER);
            assertEquals(2, breakers.size(), "seed " + seed + " CIRCUIT_BREAKER count");
            assertEquals(era5.startDay(), breakers.get(0).gameDay(), "seed " + seed + " first breaker day");
            assertEquals(era5.startDay() + 1, breakers.get(1).gameDay(), "seed " + seed + " second breaker day");
            assertTrue(breakers.get(0).params().isEmpty());
            assertTrue(breakers.get(1).params().isEmpty());
        }
    }

    @Test
    void sanctionStrikesTechStocksWithinEra7() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed);
            EraInstance era7 = eras.get(6);
            List<ScriptDayInstance> sanctions = ofType(scheduler.roll(seed, eras), ScriptType.SANCTION);
            assertTrue(sanctions.size() >= 1 && sanctions.size() <= 2,
                    "seed " + seed + " SANCTION count " + sanctions.size());
            for (ScriptDayInstance d : sanctions) {
                assertTrue(d.gameDay() >= era7.startDay() && d.gameDay() <= era7.endDay(),
                        "seed " + seed + " SANCTION day " + d.gameDay() + " outside era7");
                JsonNode params = MAPPER.readTree(d.paramsJson());
                assertTrue(params.has("targetStockId"),
                        "seed " + seed + " SANCTION paramsJson misses targetStockId");
                long targetStockId = params.get("targetStockId").asLong();
                assertTrue(TECH_STOCK_IDS.contains(targetStockId),
                        "seed " + seed + " targetStockId " + targetStockId + " not in tech pool " + TECH_STOCK_IDS);
            }
        }
    }

    @Test
    void crashWeekFallsWithinFirstHalfOfEra10() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed);
            EraInstance era10 = eras.get(9);
            int len = era10.endDay() - era10.startDay() + 1;
            int firstHalfEnd = era10.startDay() + (len + 1) / 2 - 1;
            List<ScriptDayInstance> crashes = ofType(scheduler.roll(seed, eras), ScriptType.CRASH_WEEK);
            assertTrue(crashes.size() >= 2 && crashes.size() <= 3,
                    "seed " + seed + " CRASH_WEEK count " + crashes.size());
            for (ScriptDayInstance d : crashes) {
                assertTrue(d.gameDay() >= era10.startDay() && d.gameDay() <= firstHalfEnd,
                        "seed " + seed + " CRASH_WEEK day " + d.gameDay()
                                + " outside era10 first half [" + era10.startDay() + "," + firstHalfEnd + "]");
            }
        }
    }

    @Test
    void ipoRunsTwoToThreeTimesPerYearWithUniqueNamesAndBoundedPrices() {
        int[][] yearRanges = {{1, 36}, {37, 72}, {73, 108}};
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<ScriptDayInstance> ipos = ofType(scheduler.roll(seed, eraScheduler.roll(seed)), ScriptType.IPO);
            assertTrue(ipos.size() >= 6 && ipos.size() <= 9, "seed " + seed + " IPO total " + ipos.size());
            Set<String> names = new HashSet<>();
            for (int y = 0; y < 3; y++) {
                int lo = yearRanges[y][0];
                int hi = yearRanges[y][1];
                long count = ipos.stream().filter(d -> d.gameDay() >= lo && d.gameDay() <= hi).count();
                assertTrue(count >= 2 && count <= 3,
                        "seed " + seed + " year " + (y + 1) + " IPO count " + count);
            }
            for (ScriptDayInstance d : ipos) {
                JsonNode params = MAPPER.readTree(d.paramsJson());
                assertTrue(params.has("ipoName"), "seed " + seed + " IPO paramsJson misses ipoName");
                assertTrue(params.has("issuePrice"), "seed " + seed + " IPO paramsJson misses issuePrice");
                String name = params.get("ipoName").asString();
                assertTrue(IPO_NAME_POOL.contains(name), "seed " + seed + " unknown IPO name " + name);
                assertTrue(names.add(name), "seed " + seed + " duplicated IPO name " + name);
                double price = params.get("issuePrice").asDouble();
                assertTrue(price >= 5.0 && price <= 30.0,
                        "seed " + seed + " issuePrice " + price + " outside [5,30]");
                assertEquals(Math.round(price * 100.0) / 100.0, price, 1e-9,
                        "seed " + seed + " issuePrice " + price + " not rounded to 2 decimals");
            }
        }
    }

    @Test
    void allDaysStayWithinHorizonAndSealRatiosStayBounded() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            for (ScriptDayInstance d : scheduler.roll(seed, eraScheduler.roll(seed))) {
                assertTrue(d.gameDay() >= 1 && d.gameDay() <= 108,
                        "seed " + seed + " gameDay " + d.gameDay() + " outside 1..108");
                if (d.type() == ScriptType.LIMIT_UP_ALL || d.type() == ScriptType.LIMIT_DOWN_ALL
                        || d.type() == ScriptType.CRASH_WEEK) {
                    JsonNode params = MAPPER.readTree(d.paramsJson());
                    assertTrue(params.has("sealRatio"),
                            "seed " + seed + " " + d.type() + " paramsJson misses sealRatio");
                    double sealRatio = params.get("sealRatio").asDouble();
                    assertTrue(sealRatio >= 0.85 && sealRatio <= 0.95,
                            "seed " + seed + " " + d.type() + " sealRatio " + sealRatio + " outside [0.85,0.95]");
                }
            }
        }
    }

    @Test
    void sameSeedRollsExactSameScheduleIncludingParamsJson() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<ScriptDayInstance> first = scheduler.roll(seed, eraScheduler.roll(seed));
            List<ScriptDayInstance> second = scheduler.roll(seed, eraScheduler.roll(seed));
            assertEquals(first, second, "seed " + seed + " must roll identical script days");
            for (int i = 0; i < first.size(); i++) {
                assertEquals(first.get(i).paramsJson(), second.get(i).paramsJson(),
                        "seed " + seed + " instance " + i + " paramsJson must be reproducible");
            }
        }
        for (long seed : new long[]{999L, 20240101L}) {
            List<ScriptDayInstance> first = scheduler.roll(seed, eraScheduler.roll(seed));
            List<ScriptDayInstance> second = scheduler.roll(seed, eraScheduler.roll(seed));
            assertEquals(first, second, "seed " + seed + " must roll identical script days");
        }
    }

    @Test
    void noDuplicateGameDayAndTypePairs() {
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            Set<String> pairs = new HashSet<>();
            for (ScriptDayInstance d : scheduler.roll(seed, eraScheduler.roll(seed))) {
                String key = d.gameDay() + ":" + d.type();
                assertTrue(pairs.add(key), "seed " + seed + " duplicate (gameDay,type) " + key);
            }
        }
    }

    @Test
    void outputIsSortedUnmodifiableAndSerializesEmptyParamsAsEmptyObject() {
        List<ScriptDayInstance> days = scheduler.roll(42L, eraScheduler.roll(42L));
        for (int i = 1; i < days.size(); i++) {
            ScriptDayInstance prev = days.get(i - 1);
            ScriptDayInstance curr = days.get(i);
            assertTrue(prev.gameDay() <= curr.gameDay(), "output must be sorted by gameDay ascending");
            if (prev.gameDay() == curr.gameDay()) {
                assertTrue(prev.type().ordinal() <= curr.type().ordinal(),
                        "same-day instances must be ordered by type ordinal");
            }
        }
        assertThrows(UnsupportedOperationException.class, () -> days.add(null));
        for (ScriptDayInstance d : days) {
            Map<String, Object> params = d.params();
            assertThrows(UnsupportedOperationException.class, () -> params.put("x", 1));
            if (params.isEmpty()) {
                assertEquals("{}", d.paramsJson());
            }
        }
    }

    private static List<ScriptDayInstance> ofType(List<ScriptDayInstance> days, ScriptType type) {
        return days.stream().filter(d -> d.type() == type).collect(Collectors.toList());
    }
}
