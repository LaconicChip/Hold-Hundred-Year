package com.holdhundredyear.world;

import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.ScriptType;
import com.holdhundredyear.world.EraScheduler.EraInstance;
import com.holdhundredyear.world.ScriptDayScheduler.ScriptDayInstance;
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

    /** 名场面日：能把玩家从随机游走里拽出来的那些天（打新是机制，不算名场面）。 */
    private static final Set<ScriptType> SET_PIECES = Set.of(ScriptType.CIRCUIT_BREAKER,
            ScriptType.LIMIT_DOWN_ALL, ScriptType.RESCUE, ScriptType.LIMIT_UP_ALL,
            ScriptType.SANCTION, ScriptType.CRASH_WEEK);

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
            List<ScriptDayInstance> allDays = scheduler.roll(seed, eras);
            List<ScriptDayInstance> downDays = ofType(allDays, ScriptType.LIMIT_DOWN_ALL);
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
            int rescueDay = ofType(allDays, ScriptType.RESCUE).get(0).gameDay();
            for (ScriptDayInstance d : downDays) {
                assertTrue(d.gameDay() != rescueDay && d.gameDay() != rescueDay + 1,
                        "seed " + seed + " LIMIT_DOWN_ALL day " + d.gameDay()
                                + " collides with rescue day " + rescueDay + " or its next day");
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
            // 后半段，但不许压在纪元最后一天：千股涨停是"救市次日"，压在末格就漏到下一个纪元
            int secondHalfStart = era3.startDay() + len / 2;
            int rescueDay = rescues.get(0).gameDay();
            assertTrue(rescueDay >= secondHalfStart && rescueDay <= era3.endDay() - 1,
                    "seed " + seed + " rescueDay " + rescueDay
                            + " outside era3 second half [" + secondHalfStart + "," + (era3.endDay() - 1) + "]");
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
    void compressedCalendarNeverExhaustsScriptDayPools() {
        // 24 交易日/年后短纪元变多（纪元10 可能只有 4 天，前半段只剩 2 天），
        // 撒点数一旦超过窗口容量，pickDay 就会无界重掷把注册线程卡死。
        // 现在掷骰后按容量夹逼、池子见底直接抛——扫 2000 个种子保证既不抛也不超量。
        for (long seed = 1; seed <= 2000; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed);
            List<ScriptDayInstance> days;
            try {
                days = scheduler.roll(seed, eras);
            } catch (RuntimeException e) {
                throw new AssertionError("seed " + seed + " roller exhausted a script day pool", e);
            }

            int era10FirstHalf = (eras.get(9).endDay() - eras.get(9).startDay() + 1 + 1) / 2;
            int crashes = ofType(days, ScriptType.CRASH_WEEK).size();
            assertTrue(crashes >= 2 && crashes <= era10FirstHalf,
                    "seed " + seed + " CRASH_WEEK count " + crashes + " vs era10 first half " + era10FirstHalf);

            int len3 = eras.get(2).endDay() - eras.get(2).startDay() + 1;
            int downs = ofType(days, ScriptType.LIMIT_DOWN_ALL).size();
            assertTrue(downs >= 2 && downs <= len3 - 1,
                    "seed " + seed + " LIMIT_DOWN_ALL count " + downs + " vs era3 length " + len3);

            int upDays = ofType(days, ScriptType.LIMIT_UP_ALL).size();
            assertTrue(upDays >= 2 && upDays <= 3,
                    "seed " + seed + " LIMIT_UP_ALL count " + upDays);

            long ipoDays = ofType(days, ScriptType.IPO).stream().map(ScriptDayInstance::gameDay).distinct().count();
            assertTrue(ipoDays >= 6 && ipoDays <= 9, "seed " + seed + " IPO count " + ipoDays);
        }
    }

    @Test
    void quickModeKeepsEveryScriptDayTypeInsideThirtySixDays() {
        for (long seed = 1; seed <= 500; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed, GameMode.QUICK);
            List<ScriptDayInstance> days;
            try {
                days = scheduler.roll(seed, eras, GameMode.QUICK);
            } catch (RuntimeException e) {
                throw new AssertionError("seed " + seed + " quick roller failed", e);
            }
            for (ScriptDayInstance d : days) {
                assertTrue(d.gameDay() >= 1 && d.gameDay() <= GameMode.QUICK.totalDays(),
                        "seed " + seed + " quick script day " + d.gameDay() + " outside 1..36");
            }
            for (ScriptType type : List.of(ScriptType.CIRCUIT_BREAKER, ScriptType.LIMIT_DOWN_ALL,
                    ScriptType.RESCUE, ScriptType.LIMIT_UP_ALL, ScriptType.SANCTION,
                    ScriptType.CRASH_WEEK, ScriptType.IPO)) {
                assertTrue(ofType(days, type).size() >= 1,
                        "seed " + seed + " quick mode lost script type " + type);
            }
            assertTrue(ofType(days, ScriptType.IPO).size() >= 6,
                    "seed " + seed + " quick mode IPO count too low to feel like three years");
        }
    }

    @Test
    void ipoRunsTwoToThreeTimesPerYearWithUniqueNamesAndBoundedPrices() {
        int dyp = GameMode.STANDARD.getDaysPerYear();
        int[][] yearRanges = {{1, dyp}, {dyp + 1, dyp * 2}, {dyp * 2 + 1, GameMode.STANDARD.totalDays()}};
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
                assertTrue(d.gameDay() >= 1 && d.gameDay() <= GameMode.STANDARD.totalDays(),
                        "seed " + seed + " gameDay " + d.gameDay() + " outside 1.." + GameMode.STANDARD.totalDays());
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

    /**
     * 千股跌停 → 救市 → 次日千股涨停是三天连戏，整条链必须留在纪元 3 内：
     * 救市日不能压在纪元最后一天（那样涨停就漏到下一个纪元），跌停日也必须在纪元 3 里。
     */
    @Test
    void crashRescueRallyChainStaysInsideEraThree() {
        for (GameMode mode : GameMode.values()) {
            for (long seed = 1; seed <= MAX_SEED; seed++) {
                List<EraInstance> eras = eraScheduler.roll(seed, mode);
                EraInstance era3 = eras.get(2);
                List<ScriptDayInstance> days = scheduler.roll(seed, eras, mode);
                int rescue = ofType(days, ScriptType.RESCUE).get(0).gameDay();
                assertTrue(rescue >= era3.startDay() && rescue < era3.endDay(),
                        "mode " + mode + " seed " + seed + " rescue day " + rescue
                                + " must sit inside era3 [" + era3.startDay() + "," + era3.endDay() + ")");
                assertTrue(ofType(days, ScriptType.LIMIT_UP_ALL).stream()
                                .anyMatch(d -> d.gameDay() == rescue + 1),
                        "mode " + mode + " seed " + seed + " the day after the rescue must be a rally day");
                for (ScriptDayInstance down : ofType(days, ScriptType.LIMIT_DOWN_ALL)) {
                    assertTrue(down.gameDay() >= era3.startDay() && down.gameDay() <= era3.endDay(),
                            "mode " + mode + " seed " + seed + " limit-down day " + down.gameDay()
                                    + " outside era3 [" + era3.startDay() + "," + era3.endDay() + "]");
                }
            }
        }
    }

    /**
     * 申购之后还要 D+2 上市、D+4 结算：收官前四天不再排新申购，
     * 否则玩家点完申购就终局，钱冻在一只既没上市也没结算的新股里。
     */
    @Test
    void ipoSubscribeDaysLeaveRoomForTheirLifecycle() {
        for (GameMode mode : GameMode.values()) {
            int lastSafeDay = mode.totalDays() - 4;
            for (long seed = 1; seed <= MAX_SEED; seed++) {
                List<ScriptDayInstance> days = scheduler.roll(seed, eraScheduler.roll(seed, mode), mode);
                for (ScriptDayInstance ipo : ofType(days, ScriptType.IPO)) {
                    assertTrue(ipo.gameDay() <= lastSafeDay,
                            "mode " + mode + " seed " + seed + " subscribe day " + ipo.gameDay()
                                    + " cannot finish its D+2/D+4 lifecycle before day " + mode.totalDays());
                }
            }
        }
    }

    @Test
    void quickModeCrashWeekFillsTheWholeEraInsteadOfOneDay() {
        // 快速模式纪元10 实测只有 2~3 天：窗口若仍取前半段，黑7月会退化成一天。
        int threeDayCrashes = 0;
        for (long seed = 1; seed <= 500; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed, GameMode.QUICK);
            EraInstance era10 = eras.get(9);
            int len10 = era10.endDay() - era10.startDay() + 1;
            List<ScriptDayInstance> crashes = ofType(
                    scheduler.roll(seed, eras, GameMode.QUICK), ScriptType.CRASH_WEEK);
            assertTrue(crashes.size() >= 2, "seed " + seed + " CRASH_WEEK collapsed to " + crashes.size()
                    + " day(s) inside a " + len10 + "-day era10");
            assertTrue(crashes.size() <= Math.min(3, len10), "seed " + seed + " CRASH_WEEK count "
                    + crashes.size() + " exceeds era10 length " + len10);
            for (ScriptDayInstance crash : crashes) {
                assertTrue(crash.gameDay() >= era10.startDay() && crash.gameDay() <= era10.endDay(),
                        "seed " + seed + " CRASH_WEEK day " + crash.gameDay() + " outside era10 ["
                                + era10.startDay() + "," + era10.endDay() + "]");
            }
            if (crashes.size() == 3) {
                threeDayCrashes++;
            }
        }
        assertTrue(threeDayCrashes > 0,
                "a rolled 3-day crash week must land when the era can hold it, never be clipped away");
    }

    @Test
    void quickModeCrashChainLeavesNoIdleDayInEraThree() {
        // 纪元3 恒为 4 天、股灾链正好排满：第 3 个跌停日装不下是既定取舍，写死在这里防止
        // 以后有人把纪元3 压到 3 天，把"股灾"演成"一日游"还不自知。
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            List<EraInstance> eras = eraScheduler.roll(seed, GameMode.QUICK);
            EraInstance era3 = eras.get(2);
            int len3 = era3.endDay() - era3.startDay() + 1;
            List<ScriptDayInstance> days = scheduler.roll(seed, eras, GameMode.QUICK);
            Set<Integer> chain = new HashSet<>();
            for (ScriptType type : List.of(ScriptType.LIMIT_DOWN_ALL, ScriptType.RESCUE,
                    ScriptType.LIMIT_UP_ALL)) {
                for (ScriptDayInstance day : ofType(days, type)) {
                    if (day.gameDay() >= era3.startDay() && day.gameDay() <= era3.endDay()) {
                        chain.add(day.gameDay());
                    }
                }
            }
            assertEquals(len3, chain.size(), "seed " + seed + " era3 spans " + len3
                    + " day(s) but the crash chain occupies " + chain);
        }
    }

    @Test
    void quickModeRunsDenserWithSetPiecesThanStandard() {
        // 短局靠"更密"而不是"更少"：名场面日的绝对天数两种模式差不多，但时间轴对半砍，所以占比必须更高。
        for (long seed = 1; seed <= MAX_SEED; seed++) {
            double standard = setPieceDensity(GameMode.STANDARD, seed);
            double quick = setPieceDensity(GameMode.QUICK, seed);
            assertTrue(quick > standard, "seed " + seed + " quick set-piece density " + quick
                    + " must exceed standard density " + standard);
        }
    }

    /** 同一 seed 下，名场面日占本局交易日总数的比例。 */
    private double setPieceDensity(GameMode mode, long seed) {
        List<ScriptDayInstance> days = scheduler.roll(seed, eraScheduler.roll(seed, mode), mode);
        Set<Integer> pieces = new HashSet<>();
        for (ScriptDayInstance day : days) {
            if (SET_PIECES.contains(day.type())) {
                pieces.add(day.gameDay());
            }
        }
        return pieces.size() / (double) mode.totalDays();
    }

    private static List<ScriptDayInstance> ofType(List<ScriptDayInstance> days, ScriptType type) {
        return days.stream().filter(d -> d.type() == type).collect(Collectors.toList());
    }
}
