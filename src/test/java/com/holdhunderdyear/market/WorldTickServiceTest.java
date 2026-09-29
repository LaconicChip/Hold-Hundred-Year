package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.enums.SealState;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import com.holdhunderdyear.game.constants.GameConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界一跳引擎集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖：90 跳全流程（上午 45 跳自动午休、下午 45 跳自动结算）、盘中态外静默返回、
 * 停牌冻结与指数活跃股剔除、一字板钉价不变式、熔断日开盘补强与上午提前收盘、
 * 同种子双世界逐跳精确一致、每 5 跳 checkpoint 落库。
 * 全部固定 world_seed（P6 确定性：同 seed 同日同跳必得同世界）。
 */
@SpringBootTest
@Transactional
class WorldTickServiceTest {

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private WorldTickService worldTickService;

    @Autowired
    private QuoteBoardRegistry quoteBoardRegistry;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserStockStateMapper userStockStateMapper;

    @Test
    void ninetyTicksRunFullDayThroughLunchAndSettlement() {
        long userId = worldSeeder.seedUser("tick_full1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 20260904L);

        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        for (int i = 0; i < 45; i++) {
            worldTickService.tickOne(userId);
            assertPriceInvariants(board);
        }
        assertEquals("LUNCH_BREAK", gameState(userId).getMarketState(),
                "tick 45 must hand over to lunch break automatically");
        assertEquals(45, board.getTickIndex());

        // 非盘中态静默返回：午休期间 tickOne 不推进世界（调用方负责频率）
        worldTickService.tickOne(userId);
        assertEquals(45, board.getTickIndex());
        assertEquals("LUNCH_BREAK", gameState(userId).getMarketState());

        marketStateService.resumeAfternoon(userId);
        assertEquals("AFTERNOON_OPEN", gameState(userId).getMarketState());

        for (int i = 0; i < 45; i++) {
            worldTickService.tickOne(userId);
            assertPriceInvariants(board);
        }

        UserGameState gs = gameState(userId);
        assertEquals("CLOSED", gs.getMarketState(), "tick 90 must settle the day");
        assertEquals(1, gs.getCurrentDay());
        assertEquals(90, board.getTickIndex());
        assertEquals(90, gs.getTickIndex());
        assertEquals(90, board.getIndexIntraday().size());
        for (Quote q : board.getQuotes().values()) {
            assertEquals(90, q.getIntraday().size(), "stock " + q.getStockId());
        }
    }

    @Test
    void haltedStockStaysFrozenAndIndexAveragesActiveStocksOnly() {
        long userId = worldSeeder.seedUser("tick_halt1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 424242L);
        UserStockState row = stockState(userId, 3L);
        row.setHalted(true);
        row.setHaltUntilDay(3);
        userStockStateMapper.updateById(row);

        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        worldTickService.tickOne(userId);

        Quote halted = board.quote(3L);
        assertTrue(halted.isHalted(), "halt not due must stay halted on day 1");
        assertEquals(halted.getPrevClose(), halted.getCurrent(), 1e-9, "halted price must stay frozen");
        assertEquals(0, halted.getTickCount());
        assertTrue(halted.getIntraday().isEmpty(), "halted stock must not append intraday points");
        assertEquals(23, board.getQuotes().values().stream().filter(q -> !q.isHalted()).count(),
                "active stock count must be 23");

        // 指数第 1 跳 = 23 只活跃股的等权平均收益（停牌股不进指数；同序浮点累计应精确一致）
        double weighted = 0.0;
        double weightSum = 0.0;
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            double r = (q.getIntraday().getFirst() - q.getPrevClose()) / q.getPrevClose();
            weighted += StockDefs.byId(q.getStockId()).weight() * r;
            weightSum += StockDefs.byId(q.getStockId()).weight();
        }
        assertEquals(1000.0 * (1.0 + weighted / weightSum), board.getIndexIntraday().getFirst(), 1e-9,
                "index tick 0 must average the 23 active stocks only");

        for (int i = 1; i < 90; i++) {
            worldTickService.tickOne(userId);
            if ("LUNCH_BREAK".equals(gameState(userId).getMarketState())) {
                marketStateService.resumeAfternoon(userId);
            }
        }
        assertEquals("CLOSED", gameState(userId).getMarketState());
        assertEquals(halted.getPrevClose(), halted.getCurrent(), 1e-9, "halted price must stay frozen all day");
        assertTrue(halted.getIntraday().isEmpty());
        assertEquals(90, board.getIndexIntraday().size());
    }

    @Test
    void oneWordSealedStockStaysPinnedAtItsLimitPrice() {
        long userId = worldSeeder.seedUser("tick_word1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 777L);
        UserGameState gs = gameState(userId);
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);
        setGap(userId, 2L, 0.10);

        QuoteBoard board = marketStateService.startDay(userId);

        assertEquals(2, gameState(userId).getCurrentDay(), "CLOSED startDay must open next trading day");
        Quote oneWord = board.quote(2L);
        assertEquals(SealState.UP, oneWord.getSealed(), "|gap|>=0.10 must open as one-word board");
        assertEquals(oneWord.limitUpPrice(), oneWord.getCurrent(), 1e-9);

        for (int i = 0; i < 90; i++) {
            worldTickService.tickOne(userId);
            if ("LUNCH_BREAK".equals(gameState(userId).getMarketState())) {
                marketStateService.resumeAfternoon(userId);
            }
            // 第 90 跳触发收盘结算（spec §3 步骤② prev_close 滚动为收盘价），
            // 封板钉板不变式仅对盘中 89 跳断言
            if (i < 89 && oneWord.getSealed() != SealState.NONE) {
                double limit = oneWord.getSealed() == SealState.UP
                        ? oneWord.limitUpPrice() : oneWord.limitDownPrice();
                assertEquals(limit, oneWord.getCurrent(), 1e-9, "tick " + (i + 1));
            }
        }
        assertEquals("CLOSED", gameState(userId).getMarketState());
        assertEquals(90, oneWord.getIntraday().size());
    }

    @Test
    void circuitBreakerDayClosesMarketEarlyInMorningSession() {
        long userId = worldSeeder.seedUser("tick_cb1", "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(GameConstants.DAYS_PER_YEAR); // 下一天即熔断纪元首日
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);

        QuoteBoard board = marketStateService.startDay(userId);

        assertTrue(board.isCircuitBreakerDay(), "era5 opens year 2 and is always a circuit breaker day");
        for (Quote q : board.getQuotes().values()) {
            double chg = q.getCurrent() / q.getPrevClose() - 1;
            assertTrue(chg <= -0.06 + 1e-9 && chg >= -0.08 - 1e-9,
                    "circuit open gap must be -6%~-8%, stock " + q.getStockId() + " chg=" + chg);
        }

        // 世界种子每次注册都是随机的：熔断日既可能开盘即达标，也可能全天都不到 -7%。
        // 所以断言机制本身的双向不变式——达标就必须提前收盘，未达标就必须走满 90 跳，
        // 而不是赌某个种子恰好触发了熔断。
        int ticks = 0;
        for (int i = 0; i < GameConstants.TOTAL_TICKS * 3
                && !"CLOSED".equals(gameState(userId).getMarketState()); i++) {
            if ("LUNCH_BREAK".equals(gameState(userId).getMarketState())) {
                marketStateService.resumeAfternoon(userId); // 午休是正常过场，不算"提前收盘"
            } else {
                worldTickService.tickOne(userId);
                ticks++;
            }
        }
        UserGameState after = gameState(userId);
        assertEquals("CLOSED", after.getMarketState(), "the day must settle");
        double drawdown = board.getIndexValue() / board.getPrevIndex() - 1;
        boolean breached = drawdown <= -GameConstants.CIRCUIT_BREAKER_DROP + 1e-9;
        if (breached) {
            assertTrue(after.getTickIndex() < GameConstants.TOTAL_TICKS,
                    "a >=7% index drawdown must close the day early, tickIndex=" + after.getTickIndex()
                            + " drawdown=" + drawdown);
            assertTrue(board.getTickIndex() < GameConstants.TOTAL_TICKS,
                    "board tick count must reflect the early close");
        } else {
            assertEquals(GameConstants.TOTAL_TICKS, after.getTickIndex(),
                    "without a 7% breach the circuit breaker must not fire, drawdown=" + drawdown);
        }
    }

    @Test
    void sameSeedWorldsProduceIdenticalPriceAndIndexSeries() {
        long userIdA = worldSeeder.seedUser("tick_dna1", "$2a$10$bcrypthash");
        long userIdB = worldSeeder.seedUser("tick_dnb1", "$2a$10$bcrypthash");
        long fixedSeed = 987654321L;
        setWorldSeed(userIdA, fixedSeed);
        setWorldSeed(userIdB, fixedSeed);

        QuoteBoard a = marketStateService.startDay(userIdA);
        QuoteBoard b = marketStateService.startDay(userIdB);

        for (int i = 0; i < 30; i++) {
            worldTickService.tickOne(userIdA);
            worldTickService.tickOne(userIdB);
        }

        assertEquals(a.getQuotes().keySet(), b.getQuotes().keySet());
        for (Long stockId : a.getQuotes().keySet()) {
            List<Double> seriesA = new ArrayList<>(a.quote(stockId).getIntraday());
            List<Double> seriesB = new ArrayList<>(b.quote(stockId).getIntraday());
            assertEquals(seriesA.size(), seriesB.size(), "stock " + stockId);
            for (int k = 0; k < seriesA.size(); k++) {
                assertEquals(seriesA.get(k), seriesB.get(k), 0.0,
                        "stock " + stockId + " tick " + k + " must be bit-identical");
            }
        }
        List<Double> indexA = new ArrayList<>(a.getIndexIntraday());
        List<Double> indexB = new ArrayList<>(b.getIndexIntraday());
        assertEquals(indexA.size(), indexB.size());
        for (int k = 0; k < indexA.size(); k++) {
            assertEquals(indexA.get(k), indexB.get(k), 0.0, "index tick " + k + " must be bit-identical");
        }
    }

    @Test
    void snapshotPersistsBoardPricesAndTickIndexEveryFiveTicks() {
        long userId = worldSeeder.seedUser("tick_snap1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 13579L);
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        worldTickService.tickOne(userId);
        worldTickService.tickOne(userId);
        assertEquals(2, board.getTickIndex());
        assertEquals(0, gameState(userId).getTickIndex(), "off-boundary ticks must not persist");

        for (int i = 0; i < 3; i++) {
            worldTickService.tickOne(userId);
        }
        assertEquals(5, board.getTickIndex());
        UserGameState gs = gameState(userId);
        assertEquals(5, gs.getTickIndex(), "every 5th tick must persist tickIndex");
        for (Quote q : board.getQuotes().values()) {
            UserStockState row = stockState(userId, q.getStockId());
            assertEquals(0, BigDecimal.valueOf(q.getCurrent()).compareTo(row.getCurrentPrice()),
                    "stock " + q.getStockId() + " current_price");
            assertEquals(0, BigDecimal.valueOf(q.getHigh()).compareTo(row.getHighPrice()),
                    "stock " + q.getStockId() + " high_price");
            assertEquals(0, BigDecimal.valueOf(q.getLow()).compareTo(row.getLowPrice()),
                    "stock " + q.getStockId() + " low_price");
        }
    }

    /** 每跳价格不变式：非停牌股 current 处于跌停价~涨停价区间（容差 1e-9）且 high>=current>=low。 */
    private void assertPriceInvariants(QuoteBoard board) {
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            assertTrue(q.getCurrent() >= q.limitDownPrice() - 1e-9, "stock " + q.getStockId());
            assertTrue(q.getCurrent() <= q.limitUpPrice() + 1e-9, "stock " + q.getStockId());
            assertTrue(q.getHigh() >= q.getCurrent(), "stock " + q.getStockId());
            assertTrue(q.getCurrent() >= q.getLow(), "stock " + q.getStockId());
        }
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private void setWorldSeed(long userId, long worldSeed) {
        UserGameState state = gameState(userId);
        state.setWorldSeed(worldSeed);
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
}
