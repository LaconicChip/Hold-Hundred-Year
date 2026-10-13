package com.holdhundredyear.market;

import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.content.EraNarratives;
import com.holdhundredyear.game.model.enums.EraCode;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.ScriptType;
import com.holdhundredyear.game.model.enums.SealState;
import com.holdhundredyear.game.model.enums.VolRegime;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserEraMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldRandom;
import com.holdhundredyear.world.WorldSeeder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开盘管线集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖：状态机转换、幂等开盘、非法转换、隔夜跳空落地（含一字板/停牌冻结）、
 * 纪元切换消息、波动体制掷点的确定性、剧本日强制行为（千股涨停/跌停/熔断标记/
 * 救市/制裁/黑7月）、停牌复牌补跌、反向意外日确定性与当日纪元上下文。
 */
@SpringBootTest
@Transactional
class MarketStateServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private FastForwardService fastForwardService;

    @Autowired
    private WorldTickService worldTickService;

    @Autowired
    private QuoteBoardRegistry quoteBoardRegistry;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserStockStateMapper userStockStateMapper;

    @Autowired
    private UserDailySnapshotMapper userDailySnapshotMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Autowired
    private UserEraMapper userEraMapper;

    @Autowired
    private UserScriptDayMapper userScriptDayMapper;

    @Test
    void startDayFromNotStartedOpensMorningSession() {
        long userId = worldSeeder.seedUser("mkt_open1", "$2a$10$bcrypthash");

        QuoteBoard board = marketStateService.startDay(userId);

        UserGameState state = gameState(userId);
        assertEquals("MORNING_OPEN", state.getMarketState());
        assertEquals(1, state.getCurrentDay());
        assertEquals(0, state.getTickIndex());
        assertEquals(24, board.getQuotes().size());
        assertEquals(0, board.getTickIndex());
        assertEquals(0, board.getShocks().activeCount());
    }

    @Test
    void startDayInMorningOpenIsIdempotentNoOp() {
        long userId = worldSeeder.seedUser("mkt_idle1", "$2a$10$bcrypthash");
        QuoteBoard first = marketStateService.startDay(userId);
        // 第 1 日开盘必然推纪元叙事；若随机种子的打新申购日恰逢 day1 还会多推一条新股申购
        // 通知（onDayOpen），故只对纪元消息计数（幂等检查关心的是纪元消息不重复）。
        assertEquals(1, eraStartMessageCount(userId), "era-1 start message expected");

        UserGameState state = gameState(userId);
        state.setTickIndex(5);
        userGameStateMapper.updateById(state);

        QuoteBoard second = marketStateService.startDay(userId);

        assertSame(first, second, "idempotent path must return the cached board");
        UserGameState after = gameState(userId);
        assertEquals("MORNING_OPEN", after.getMarketState());
        assertEquals(1, after.getCurrentDay(), "currentDay must not advance");
        assertEquals(5, after.getTickIndex(), "tickIndex must not be reset");
        assertEquals(1, eraStartMessageCount(userId), "no duplicate era message");
    }

    @Test
    void startDayFromLunchBreakThrowsNotTradingTime() {
        long userId = worldSeeder.seedUser("mkt_lunch1", "$2a$10$bcrypthash");
        setMarketState(userId, "LUNCH_BREAK");

        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(userId));
        assertEquals(ErrorCode.NOT_TRADING_TIME, ex.getErrorCode());
    }

    @Test
    void startDayFromSettlingThrowsNotTradingTime() {
        long userId = worldSeeder.seedUser("mkt_settle1", "$2a$10$bcrypthash");
        setMarketState(userId, "SETTLING");

        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(userId));
        assertEquals(ErrorCode.NOT_TRADING_TIME, ex.getErrorCode());
    }

    @Test
    void startDayFromFinishedThrowsFinishedState() {
        long userId = worldSeeder.seedUser("mkt_fin1", "$2a$10$bcrypthash");
        setMarketState(userId, "FINISHED");

        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(userId));
        assertEquals(ErrorCode.FINISHED_STATE, ex.getErrorCode());
    }

    @Test
    void startDayFromBankruptThrowsFinishedState() {
        long userId = worldSeeder.seedUser("mkt_bank1", "$2a$10$bcrypthash");
        setMarketState(userId, "BANKRUPT");

        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(userId));
        assertEquals(ErrorCode.FINISHED_STATE, ex.getErrorCode());
    }

    @Test
    void startDayForUnknownUserThrowsNotFound() {
        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(999999L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void startDayFromClosedAppliesOvernightGapsAndSealsOneWordBoard() {
        long userId = worldSeeder.seedUser("mkt_gap1", "$2a$10$bcrypthash");
        QuoteBoard day1 = marketStateService.startDay(userId);
        // 模拟昨日盘中遗留：缓存板上的陈旧冲击与 tick 进度，开盘后必须被清掉
        day1.setTickIndex(7);

        setMarketState(userId, "CLOSED");
        setGap(userId, 1L, 0.05);
        setGap(userId, 2L, 0.10);
        UserStockState haltedRow = stockState(userId, 3L);
        haltedRow.setHalted(true);
        haltedRow.setGapPct(0.05);
        userStockStateMapper.updateById(haltedRow);

        QuoteBoard board = marketStateService.startDay(userId);

        UserGameState state = gameState(userId);
        assertEquals("MORNING_OPEN", state.getMarketState());
        assertEquals(2, state.getCurrentDay(), "CLOSED -> startDay must advance to next trading day");
        assertEquals(0, state.getTickIndex());
        assertEquals(expectedVolRegime(state.getWorldSeed(), 2).name(), state.getVolRegime());

        assertEquals(0, board.getTickIndex(), "board tickIndex must reset on re-open");
        assertEquals(0, board.getShocks().activeCount(), "stale shocks must be cleared on re-open");
        assertSame(board, quoteBoardRegistry.getOrLoad(userId), "registry must cache the opened board");
        assertEquals(1, eraStartMessageCount(userId), "day 2 stays in era 1, no new era message");

        Quote gapped = board.quote(1L);
        double expected = Quote.round2(gapped.getPrevClose() * 1.05);
        assertEquals(expected, gapped.getCurrent(), 1e-9);
        assertEquals(expected, gapped.getOpen(), 1e-9);
        assertEquals(expected, gapped.getHigh(), 1e-9);
        assertEquals(expected, gapped.getLow(), 1e-9);
        assertEquals(SealState.NONE, gapped.getSealed());

        Quote oneWord = board.quote(2L);
        assertEquals(Quote.round2(oneWord.getPrevClose() * 1.10), oneWord.getCurrent(), 1e-9);
        assertEquals(SealState.UP, oneWord.getSealed(), "|gap|>=0.10 must seal first tick");
        assertTrue(oneWord.getSealTicksLeft() >= GameConstants.SEAL_MIN_TICKS);

        Quote frozen = board.quote(3L);
        assertEquals(120.0, frozen.getCurrent(), 1e-9, "halted stock price must stay frozen");
        assertEquals(SealState.NONE, frozen.getSealed());

        UserStockState row = stockState(userId, 1L);
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(row.getCurrentPrice()), "currentPrice");
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(row.getOpenPrice()), "openPrice");
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(row.getHighPrice()), "highPrice");
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(row.getLowPrice()), "lowPrice");
        assertEquals(0.0, row.getGapPct(), 0.0, "applied gap must be consumed");

        UserStockState frozenRow = stockState(userId, 3L);
        assertEquals(0.05, frozenRow.getGapPct(), 0.0, "halted stock gap must be preserved for later");
    }

    @Test
    void startDayOnDayOnePushesEraStartNotifyMessage() {
        long userId = worldSeeder.seedUser("mkt_era1", "$2a$10$bcrypthash");

        marketStateService.startDay(userId);

        // 第 1 日开盘必然推纪元叙事消息；此外若随机种子的打新申购日恰逢 day1，会多推一条
        // "新股申购"通知（onDayOpen）。故按纪元消息查找断言其属性，而非断言消息总数。
        UserMessage msg = messages(userId).stream()
                .filter(m -> m.getTitle().startsWith("纪元切换"))
                .findFirst().orElse(null);
        assertNotNull(msg, "day-1 open must push the era-start notify message");
        assertEquals("SYSTEM", msg.getSource());
        assertEquals("NOTIFY", msg.getType());
        assertEquals(1, msg.getGameDay());
        assertFalse(msg.getIsRead());
        assertNull(msg.getTruthStatus());
        assertTrue(msg.getTitle().contains(EraCode.BULL_START.getName()),
                "title must read the era's Chinese name, title=" + msg.getTitle());
        assertFalse(msg.getTitle().contains("BULL_START"),
                "player-facing title must not leak the enum name, title=" + msg.getTitle());
        assertEquals(EraNarratives.narrative(EraCode.BULL_START), msg.getContent(),
                "era notice must carry the era narrative, not a placeholder");
        assertFalse(msg.getContent().contains("占位"), "no placeholder copy may reach players");
    }

    @Test
    void startDayIntoNewEraPushesEraSwitchNotifyMessage() {
        long userId = worldSeeder.seedUser("mkt_era2", "$2a$10$bcrypthash");
        UserEra era2 = userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                        .eq(UserEra::getUserId, userId)
                        .orderByAsc(UserEra::getEraIndex))
                .get(1);

        UserGameState state = gameState(userId);
        state.setCurrentDay(era2.getStartDay() - 1);
        state.setMarketState("CLOSED");
        userGameStateMapper.updateById(state);

        marketStateService.startDay(userId);

        assertEquals(era2.getStartDay(), gameState(userId).getCurrentDay());
        // 随机种子的打新申购/中签/上市日恰逢纪元首日时会多推一条打新通知（onDayOpen），
        // 故按纪元消息过滤断言其属性与唯一性，而非断言消息总数（同 day-1 测试的防御模式）。
        List<UserMessage> eraMsgs = messages(userId).stream()
                .filter(m -> m.getTitle() != null && m.getTitle().startsWith("纪元切换"))
                .collect(Collectors.toList());
        assertEquals(1, eraMsgs.size(), "exactly one era-switch notify message expected");
        UserMessage msg = eraMsgs.get(0);
        assertEquals(era2.getStartDay(), msg.getGameDay());
        assertEquals("SYSTEM", msg.getSource());
        assertEquals("NOTIFY", msg.getType());
        EraCode switched = EraCode.valueOf(era2.getEraCode());
        assertEquals("纪元切换：" + switched.getName(), msg.getTitle(),
                "era-switch title is the Chinese era name only, no enum code");
        assertEquals(EraNarratives.narrative(EraCode.valueOf(era2.getEraCode())), msg.getContent(),
                "era-switch notice must read the new era's narrative");
    }

    @Test
    void openDayStampsPrevIndexAsYesterdayCloseIndex() {
        long userId = worldSeeder.seedUser("mkt_pidx1", "$2a$10$bcrypthash");

        marketStateService.startDay(userId);
        assertEquals(GameConstants.INITIAL_INDEX, gameState(userId).getPrevIndex(), 1e-9,
                "day 1 has no yesterday, so the 1000 base index is its opening baseline");

        fastForwardService.skip(userId);
        double dayOneClose = gameState(userId).getIndexValue();

        marketStateService.startDay(userId);
        assertEquals(dayOneClose, gameState(userId).getPrevIndex(), 1e-9,
                "day 2 must stamp day 1's close index as its own baseline");
    }

    @Test
    void restartOnCircuitBreakerDayKeepsBreakerBaseline() {
        long userId = worldSeeder.seedUser("mkt_pidx2", "$2a$10$bcrypthash");
        UserScriptDay breaker = firstScriptDay(userId, ScriptType.CIRCUIT_BREAKER);

        QuoteBoard board = startDayAt(userId, breaker.getGameDay());
        double baseline = board.getPrevIndex();
        assertTrue(baseline > 0, "breaker day must open with a positive index baseline");

        for (int i = 0; i < 10; i++) {
            worldTickService.tickOne(userId);
        }
        double intraday = gameState(userId).getIndexValue();
        assertTrue(Math.abs(intraday - baseline) > 1e-9,
                "intraday index has to drift from the baseline, otherwise this test proves nothing");

        // 进程重启：内存板丢失，只能靠 Checkpoint 恢复
        quoteBoardRegistry.evict(userId);
        QuoteBoard restored = quoteBoardRegistry.getOrLoad(userId);

        assertEquals(baseline, restored.getPrevIndex(), 1e-9,
                "熔断阈值基数必须是当日开盘昨收，重启后不得退化为最近一次盘中 checkpoint");
    }

    @Test
    void startDayRollsSeededDeterministicVolRegime() {
        long userId = worldSeeder.seedUser("mkt_vol1", "$2a$10$bcrypthash");

        marketStateService.startDay(userId);

        UserGameState state = gameState(userId);
        String regime = state.getVolRegime();
        assertTrue(regime.equals("CALM") || regime.equals("NORMAL") || regime.equals("WILD"),
                "vol_regime must be one of CALM/NORMAL/WILD, got " + regime);
        assertEquals(expectedVolRegime(state.getWorldSeed(), 1).name(), regime,
                "same seed must roll the same regime");
    }

    @Test
    void startDayOnLimitUpAllDayForcesSealUpOnRoundOf24BySealRatio() {
        long userId = worldSeeder.seedUser("mkt_upall1", "$2a$10$bcrypthash");
        UserScriptDay script = firstScriptDay(userId, ScriptType.LIMIT_UP_ALL);
        double sealRatio = MAPPER.readTree(script.getParamsJson()).get("sealRatio").asDouble();

        QuoteBoard board = startDayAt(userId, script.getGameDay());

        assertEquals(script.getGameDay(), gameState(userId).getCurrentDay());
        int expectedSealed = (int) Math.round(board.getQuotes().size() * sealRatio);
        List<Quote> sealed = board.getQuotes().values().stream()
                .filter(q -> q.getSealed() == SealState.UP)
                .collect(Collectors.toList());
        assertEquals(expectedSealed, sealed.size(), "sealRatio=" + sealRatio);
        for (Quote q : sealed) {
            assertEquals(q.limitUpPrice(), q.getCurrent(), 1e-9, "stock " + q.getStockId());
            assertEquals(GameConstants.TOTAL_TICKS, q.getSealTicksLeft(), "stock " + q.getStockId());
            assertEquals(0, BigDecimal.valueOf(q.limitUpPrice())
                    .compareTo(stockState(userId, q.getStockId()).getCurrentPrice()),
                    "stock " + q.getStockId() + " current_price must persist the forced seal");
        }
        for (Quote q : board.getQuotes().values()) {
            if (q.getSealed() != SealState.UP) {
                assertEquals(SealState.NONE, q.getSealed(), "survivor stock " + q.getStockId());
            }
        }
    }

    @Test
    void startDayOnLimitDownAllDayForcesSealDownOnRoundOf24BySealRatio() {
        long userId = worldSeeder.seedUser("mkt_dnall1", "$2a$10$bcrypthash");
        UserScriptDay script = firstScriptDay(userId, ScriptType.LIMIT_DOWN_ALL);
        double sealRatio = MAPPER.readTree(script.getParamsJson()).get("sealRatio").asDouble();

        QuoteBoard board = startDayAt(userId, script.getGameDay());

        // 首个跌停日与"停牌潮"同日：那几只连板股在钉板前就被冻住，跌停板只能钉在还能交易的
        // 那批上——取"比例×总数"与"可交易数"两者的小值（这就是"停牌躲过股灾"的字面意思）
        int tradable = (int) board.getQuotes().values().stream().filter(q -> !q.isHalted()).count();
        int expectedSealed = Math.min((int) Math.round(board.getQuotes().size() * sealRatio), tradable);
        List<Quote> sealed = board.getQuotes().values().stream()
                .filter(q -> q.getSealed() == SealState.DOWN)
                .collect(Collectors.toList());
        assertEquals(expectedSealed, sealed.size(), "sealRatio=" + sealRatio + " tradable=" + tradable);
        for (Quote q : sealed) {
            assertEquals(q.limitDownPrice(), q.getCurrent(), 1e-9, "stock " + q.getStockId());
            assertEquals(GameConstants.TOTAL_TICKS, q.getSealTicksLeft(), "stock " + q.getStockId());
            assertEquals(0, BigDecimal.valueOf(q.limitDownPrice())
                    .compareTo(stockState(userId, q.getStockId()).getCurrentPrice()),
                    "stock " + q.getStockId() + " current_price must persist the forced seal");
        }
        for (Quote q : board.getQuotes().values()) {
            if (q.getSealed() != SealState.DOWN) {
                assertEquals(SealState.NONE, q.getSealed(), "survivor stock " + q.getStockId());
            }
        }
    }

    @Test
    void startDayRecalculatesOpeningIndexFromAllOpenPriceMoves() {
        long userId = worldSeeder.seedUser("mkt_idx1", "$2a$10$bcrypthash");
        UserScriptDay script = firstScriptDay(userId, ScriptType.LIMIT_DOWN_ALL);

        QuoteBoard board = startDayAt(userId, script.getGameDay());

        // 期望：昨收 × (1 + 未停牌股等权平均开盘收益)——跳空/钉板/复牌补跌全部入指数
        double prevIndex = 1000.0;
        double avgOpenReturn = board.getQuotes().values().stream()
                .filter(q -> !q.isHalted())
                .mapToDouble(q -> (q.getCurrent() - q.getPrevClose()) / q.getPrevClose())
                .average().orElse(0.0);
        assertEquals(prevIndex * (1 + avgOpenReturn), board.getIndexValue(), 1e-6,
                "开盘指数必须等于昨收×(1+等权平均开盘收益)");
        assertTrue(board.getIndexValue() < prevIndex * 0.95,
                "千股跌停日开盘指数必须明显下跌（spec §4.2），而非停在昨收");
    }

    @Test
    void limitDownAllDaySettlesWithIndexDropMatchingAverageStockDrop() {
        long userId = worldSeeder.seedUser("mkt_idx2", "$2a$10$bcrypthash");
        UserScriptDay script = firstScriptDay(userId, ScriptType.LIMIT_DOWN_ALL);
        // 从剧本日前一日收盘态快进整日（开盘钉板 → 盘中 → 结算）
        UserGameState state = gameState(userId);
        state.setCurrentDay(script.getGameDay() - 1);
        state.setMarketState("CLOSED");
        userGameStateMapper.updateById(state);
        fastForwardService.skip(userId);

        UserDailySnapshot snapshot = userDailySnapshotMapper.selectOne(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .eq(UserDailySnapshot::getGameDay, script.getGameDay()));
        assertNotNull(snapshot, "skip 后必须结算出当日快照");
        assertTrue(snapshot.getIndexDayPct() < -5.0,
                "千股跌停日指数日跌幅必须显著（≥85% 股票 -10%），实际 " + snapshot.getIndexDayPct());
        double avgStockPct = userStockStateMapper.selectList(new LambdaQueryWrapper<UserStockState>()
                        .eq(UserStockState::getUserId, userId))
                .stream()
                .filter(s -> !Boolean.TRUE.equals(s.getHalted()))
                .mapToDouble(s -> s.getDayChangePct() != null ? s.getDayChangePct() * 100 : 0.0)
                .average().orElse(0.0);
        assertEquals(avgStockPct, snapshot.getIndexDayPct(), 1.0,
                "指数日跌幅必须与未停牌个股平均跌幅一致（等权复合，容差 1pp）");
    }

    @Test
    void startDayOnCircuitBreakerDaySetsBoardFlag() {
        long userId = worldSeeder.seedUser("mkt_cb1", "$2a$10$bcrypthash");

        QuoteBoard lastDayOfYear1 = startDayAt(userId, GameMode.STANDARD.getDaysPerYear());
        assertFalse(lastDayOfYear1.isCircuitBreakerDay(), "era4 tail is not a circuit breaker day");

        QuoteBoard firstDayOfYear2 = startDayAt(userId, GameMode.STANDARD.getDaysPerYear() + 1);
        assertTrue(firstDayOfYear2.isCircuitBreakerDay(), "era5 always starts year 2 with CIRCUIT_BREAKER script rows");
    }

    @Test
    void startDayOnRescueDayEnqueuesOnceMarketWideShock() {
        long userId = worldSeeder.seedUser("mkt_rescue1", "$2a$10$bcrypthash");
        int rescueDay = firstScriptDay(userId, ScriptType.RESCUE).getGameDay();

        QuoteBoard board = startDayAt(userId, rescueDay);

        assertEquals(rescueDay, gameState(userId).getCurrentDay());
        assertEquals(1, board.getShocks().activeCount());
        double consumed = board.getShocks().consumeMarket();
        assertTrue(consumed >= 0.02 - 1e-9 && consumed <= 0.04 + 1e-9,
                "rescue shock magnitude must be 2%~4%, got " + consumed);
        assertEquals(0, board.getShocks().activeCount(), "ONCE shock must exhaust after first consume");
    }

    @Test
    void startDayOnSanctionDayPinsTargetStockAtLimitDown() {
        long userId = worldSeeder.seedUser("mkt_sanc1", "$2a$10$bcrypthash");
        UserScriptDay script = firstScriptDay(userId, ScriptType.SANCTION);
        long targetStockId = MAPPER.readTree(script.getParamsJson()).get("targetStockId").asLong();

        QuoteBoard board = startDayAt(userId, script.getGameDay());

        Quote target = board.quote(targetStockId);
        assertEquals(SealState.DOWN, target.getSealed());
        assertEquals(target.limitDownPrice(), target.getCurrent(), 1e-9);
        assertEquals(GameConstants.TOTAL_TICKS, target.getSealTicksLeft());
        assertEquals(0, BigDecimal.valueOf(target.limitDownPrice())
                .compareTo(stockState(userId, targetStockId).getCurrentPrice()));
        for (Quote q : board.getQuotes().values()) {
            if (q.getStockId() != targetStockId) {
                assertEquals(SealState.NONE, q.getSealed(), "non-target stock " + q.getStockId());
            }
        }
    }

    /**
     * 妖股三幕·第一幕（spec §4.4 连板日）：名单里的票全部钉在涨停价。
     * 若它们已被别的剧本冻住（停牌中价格不动），那就跳过——连板不能覆盖停牌。
     */
    @Test
    void startDayOnHotStreakDayPinsTheNamedStocksLimitUp() {
        long userId = worldSeeder.seedUser("mkt_streak1", "$2a$10$bcrypthash");
        UserScriptDay streak = firstScriptDay(userId, ScriptType.HOT_STREAK);
        List<Long> hot = stockIdsOf(streak);

        QuoteBoard board = startDayAt(userId, streak.getGameDay());

        for (Long stockId : hot) {
            Quote quote = board.quote(stockId);
            if (quote.isHalted()) {
                continue;
            }
            assertEquals(SealState.UP, quote.getSealed(), "hot stock " + stockId + " must open sealed up");
            assertEquals(quote.limitUpPrice(), quote.getCurrent(), 1e-9,
                    "a 连板 stock must sit exactly on its limit-up price");
        }
    }

    /**
     * 妖股三幕·第二幕（停牌潮）：股灾当天名单里的票被集体冻住，而且不能被同日的
     * "千股跌停"一起钉成跌停板——前置阶段的顺序就是这里守的。同时必须发通知，
     * 否则玩家次日点进去只会以为系统坏了。
     */
    @Test
    void startDayOnHaltSwarmDayFreezesHotStocksInsteadOfSealingThemDown() {
        long userId = worldSeeder.seedUser("mkt_swarm1", "$2a$10$bcrypthash");
        UserScriptDay swarm = firstScriptDay(userId, ScriptType.HALT_SWARM);
        int haltDays = MAPPER.readTree(swarm.getParamsJson()).get("haltDays").asInt();
        List<Long> hot = stockIdsOf(swarm);

        QuoteBoard board = startDayAt(userId, swarm.getGameDay());

        for (Long stockId : hot) {
            Quote quote = board.quote(stockId);
            assertTrue(quote.isHalted(), "hot stock " + stockId + " must be halted on the crash day");
            assertNotEquals(SealState.DOWN, quote.getSealed(),
                    "the swarm must be frozen BEFORE the market-wide limit-down seal, not pinned with it");
            assertEquals(swarm.getGameDay() + haltDays, stockState(userId, stockId).getHaltUntilDay(),
                    "halt_until_day must come from the script, not from the 三连板 default");
        }
        assertTrue(messages(userId).stream().anyMatch(m -> m.getTitle().contains("停牌潮")),
                "the swarm has to be announced, or the player cannot tell why these stocks are untradeable");
    }

    /** 妖股三幕·第三幕（一字断魂刀）：目标股开盘即跌停、全天封死，并有一条台词。 */
    @Test
    void startDayOnHunDaoDayPinsTheTargetAtLimitDownAndNotifies() {
        long userId = worldSeeder.seedUser("mkt_dao1", "$2a$10$bcrypthash");
        UserScriptDay dao = firstScriptDay(userId, ScriptType.HUN_DAO);
        long target = MAPPER.readTree(dao.getParamsJson()).get("targetStockId").asLong();

        QuoteBoard board = startDayAt(userId, dao.getGameDay());

        Quote quote = board.quote(target);
        if (!quote.isHalted()) {
            assertEquals(SealState.DOWN, quote.getSealed(), "the knife stock must open at its limit-down price");
            assertEquals(quote.limitDownPrice(), quote.getCurrent(), 1e-9);
        }
        assertTrue(messages(userId).stream().anyMatch(m -> m.getTitle().contains("一字断魂刀")),
                "the knife must be announced, it is the payoff of the whole three-act setup");
    }

    /** 剧本参数里的 stockIds（名单三幕共用一份）。 */
    private static List<Long> stockIdsOf(UserScriptDay scriptDay) {
        List<Long> ids = new ArrayList<>();
        MAPPER.readTree(scriptDay.getParamsJson()).path("stockIds").forEach(node -> ids.add(node.asLong()));
        return ids;
    }

    @Test
    void startDayOnCrashWeekDayPinsTechDownAndDefensiveUp() {
        long userId = worldSeeder.seedUser("mkt_crash1", "$2a$10$bcrypthash");
        int crashDay = firstScriptDay(userId, ScriptType.CRASH_WEEK).getGameDay();
        Set<Long> techDown = StockDefs.ALL.stream()
                .filter(s -> Set.of("半导体", "算力", "通信").contains(s.sector()))
                .map(StockDefs.StockDef::id)
                .collect(Collectors.toSet());
        Set<Long> defensiveUp = StockDefs.ALL.stream()
                .filter(s -> Set.of("银行", "煤炭").contains(s.sector()))
                .map(StockDefs.StockDef::id)
                .collect(Collectors.toSet());
        assertEquals(Set.of(2L, 15L, 24L), techDown);
        assertEquals(Set.of(7L, 18L), defensiveUp);

        QuoteBoard board = startDayAt(userId, crashDay);

        for (Long id : techDown) {
            Quote q = board.quote(id);
            assertEquals(SealState.DOWN, q.getSealed(), "tech stock " + id);
            assertEquals(q.limitDownPrice(), q.getCurrent(), 1e-9, "tech stock " + id);
            assertEquals(GameConstants.TOTAL_TICKS, q.getSealTicksLeft(), "tech stock " + id);
        }
        for (Long id : defensiveUp) {
            Quote q = board.quote(id);
            assertEquals(SealState.UP, q.getSealed(), "defensive stock " + id);
            assertEquals(q.limitUpPrice(), q.getCurrent(), 1e-9, "defensive stock " + id);
            assertEquals(GameConstants.TOTAL_TICKS, q.getSealTicksLeft(), "defensive stock " + id);
        }
        for (Quote q : board.getQuotes().values()) {
            if (!techDown.contains(q.getStockId()) && !defensiveUp.contains(q.getStockId())) {
                assertEquals(SealState.NONE, q.getSealed(), "bystander stock " + q.getStockId());
            }
        }
    }

    @Test
    void startDayResumesDueHaltWithCatchUpOrMonsterGapAndFreezesUndueHalt() {
        long userId = worldSeeder.seedUser("mkt_resume1", "$2a$10$bcrypthash");
        int day = quietDay(userId);

        UserStockState due = stockState(userId, 1L);
        due.setHalted(true);
        due.setHaltUntilDay(day);
        due.setLimitUpStreak(2);
        due.setGapPct(0.03);
        userStockStateMapper.updateById(due);

        UserStockState undue = stockState(userId, 2L);
        undue.setHalted(true);
        undue.setHaltUntilDay(day + 3);
        undue.setGapPct(0.04);
        userStockStateMapper.updateById(undue);

        QuoteBoard board = startDayAt(userId, day);

        Quote resumed = board.quote(1L);
        assertFalse(resumed.isHalted(), "halt due today must resume");
        boolean monster = Math.abs(resumed.getCurrent() - resumed.limitUpPrice()) < 1e-9;
        boolean catchUp = resumed.getCurrent() >= resumed.getPrevClose() * 0.90 - 0.006
                && resumed.getCurrent() <= resumed.getPrevClose() * 0.95 + 0.006;
        assertTrue(monster || catchUp,
                "resume must catch up -5%~-10% or surge to limit up, current=" + resumed.getCurrent());
        UserStockState resumedRow = stockState(userId, 1L);
        assertFalse(resumedRow.getHalted(), "resumed halt must persist halted=false");
        assertNull(resumedRow.getHaltUntilDay(), "resumed halt must persist halt_until_day=null");
        assertEquals(0, resumedRow.getLimitUpStreak(), "resume must reset limit_up_streak");
        assertEquals(0.0, resumedRow.getGapPct(), 0.0, "stale gap must be consumed on resume");
        assertEquals(0, BigDecimal.valueOf(resumed.getCurrent()).compareTo(resumedRow.getCurrentPrice()),
                "current_price must persist the resume gap");

        Quote frozen = board.quote(2L);
        assertTrue(frozen.isHalted(), "halt not due must stay halted");
        assertEquals(frozen.getPrevClose(), frozen.getCurrent(), 1e-9, "undue halt price stays frozen");
        UserStockState frozenRow = stockState(userId, 2L);
        assertTrue(frozenRow.getHalted());
        assertEquals(day + 3, frozenRow.getHaltUntilDay());
        assertEquals(0.04, frozenRow.getGapPct(), 0.0, "frozen gap must be preserved while halted");
    }

    @Test
    void startDayReverseDayFlagIsDeterministicPerSeed() {
        long userIdA = worldSeeder.seedUser("mkt_rev_a", "$2a$10$bcrypthash");
        long userIdB = worldSeeder.seedUser("mkt_rev_b", "$2a$10$bcrypthash");
        long fixedSeed = 20260904L;
        setWorldSeed(userIdA, fixedSeed);
        setWorldSeed(userIdB, fixedSeed);

        QuoteBoard boardA = marketStateService.startDay(userIdA);
        QuoteBoard boardB = marketStateService.startDay(userIdB);

        assertEquals(boardA.isReverseDay(), boardB.isReverseDay(),
                "same seed must roll the identical reverse-day flag");
        boolean expected = WorldRandom.of(fixedSeed, "reverse" + 1).nextDouble() < 0.08;
        assertEquals(expected, boardA.isReverseDay());
        assertEquals(expected, boardB.isReverseDay());
    }

    @Test
    void startDaySetsCurrentEraContextOnBoard() {
        long userId = worldSeeder.seedUser("mkt_eractx1", "$2a$10$bcrypthash");

        QuoteBoard day1 = marketStateService.startDay(userId);
        assertEquals(1, day1.getCurrentEra().eraIndex());
        assertEquals(1, day1.getCurrentEra().startDay());

        int era5Start = GameMode.STANDARD.getDaysPerYear() + 1;
        QuoteBoard onEra5 = startDayAt(userId, era5Start);
        assertEquals(5, onEra5.getCurrentEra().eraIndex());
        assertEquals(era5Start, onEra5.getCurrentEra().startDay());
        assertEquals(era5Start, gameState(userId).getCurrentDay());
    }

    @Test
    void quickModeBoardContextFollowsTheQuickEraSchedule() {
        long userId = worldSeeder.seedUser("mkt_eraquick", "$2a$10$bcrypthash", GameMode.QUICK);

        // 快速模式一年 12 天：第 13 天已进第二个游戏年（纪元 5 熔断开局），
        // 标准日历的第 13 天却还停在纪元 3。纪元带 muMkt 与板块系数，漏传模式
        // 就是"通知写着股灾、盘面按牛市漂"，所以这里拿播种的 user_era 行当权威比对。
        int day = GameMode.QUICK.getDaysPerYear() + 1;
        QuoteBoard board = startDayAt(userId, day);
        UserEra seeded = userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                        .eq(UserEra::getUserId, userId)).stream()
                .filter((era) -> era.getStartDay() <= day && day <= era.getEndDay())
                .findFirst().orElseThrow();

        assertEquals(seeded.getEraIndex().intValue(), board.getCurrentEra().eraIndex(),
                "quick-mode board era must be the seeded quick schedule, not the standard one");
        assertEquals(seeded.getEndDay().intValue(), board.getCurrentEra().endDay(),
                "era span must match the seeded user_era row");
        assertTrue(board.getCurrentEra().endDay() <= GameMode.QUICK.totalDays(),
                "a standard-calendar era would reach past the quick horizon: " + board.getCurrentEra().endDay());
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private void setMarketState(long userId, String marketState) {
        UserGameState state = gameState(userId);
        state.setMarketState(marketState);
        userGameStateMapper.updateById(state);
    }

    private UserStockState stockState(long userId, long stockId) {
        return userStockStateMapper.selectOne(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId)
                .eq(UserStockState::getStockId, stockId));
    }

    private void setGap(long userId, long stockId, double gapPct) {
        UserStockState state = stockState(userId, stockId);
        state.setGapPct(gapPct);
        userStockStateMapper.updateById(state);
    }

    private List<UserMessage> messages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
    }

    /** 第 1 日纪元叙事消息数（title 以"纪元切换"开头；不计打新申购等其它通知）。 */
    private long eraStartMessageCount(long userId) {
        return messages(userId).stream()
                .filter(m -> m.getTitle() != null && m.getTitle().startsWith("纪元切换"))
                .count();
    }

    /** 与实现保持同一掷点协议：WorldRandom.of(seed, "volregime"+day)，p<0.2 CALM、p<0.8 NORMAL、否则 WILD。 */
    private static VolRegime expectedVolRegime(long worldSeed, int currentDay) {
        Random rng = WorldRandom.of(worldSeed, "volregime" + currentDay);
        double p = rng.nextDouble();
        if (p < 0.2) {
            return VolRegime.CALM;
        }
        if (p < 0.8) {
            return VolRegime.NORMAL;
        }
        return VolRegime.WILD;
    }

    /** 把用户推到 current_day=day-1 + CLOSED，使 startDay 恰好开启第 day 个交易日。 */
    private QuoteBoard startDayAt(long userId, int day) {
        UserGameState state = gameState(userId);
        state.setCurrentDay(day - 1);
        state.setMarketState("CLOSED");
        userGameStateMapper.updateById(state);
        return marketStateService.startDay(userId);
    }

    /** 该用户指定类型最早的一个剧本日。 */
    private UserScriptDay firstScriptDay(long userId, ScriptType type) {
        return userScriptDayMapper.selectList(new LambdaQueryWrapper<UserScriptDay>()
                        .eq(UserScriptDay::getUserId, userId)
                        .eq(UserScriptDay::getScriptType, type.name())
                        .orderByAsc(UserScriptDay::getGameDay))
                .get(0);
    }

    /** 找一个没有任何剧本日行的交易日（>=2），供复牌等与剧本无关的断言使用。 */
    private int quietDay(long userId) {
        Set<Integer> scripted = userScriptDayMapper.selectList(new LambdaQueryWrapper<UserScriptDay>()
                        .eq(UserScriptDay::getUserId, userId))
                .stream()
                .map(UserScriptDay::getGameDay)
                .collect(Collectors.toSet());
        for (int day = 2; day <= GameMode.STANDARD.totalDays(); day++) {
            if (!scripted.contains(day)) {
                return day;
            }
        }
        throw new IllegalStateException("no script-free trading day found");
    }

    private void setWorldSeed(long userId, long worldSeed) {
        UserGameState state = gameState(userId);
        state.setWorldSeed(worldSeed);
        userGameStateMapper.updateById(state);
    }
}
