package com.holdhundredyear.market;

import com.holdhundredyear.game.constants.StockDefs;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.constants.PreHistoryDefs;
import com.holdhundredyear.game.model.dto.DailySummaryDTO;
import com.holdhundredyear.game.model.dto.EquityCurveDTO;
import com.holdhundredyear.game.model.dto.IndexPointDTO;
import com.holdhundredyear.game.model.dto.IntradayPointDTO;
import com.holdhundredyear.game.model.dto.KlinePointDTO;
import com.holdhundredyear.game.model.dto.LeaderboardDTO;
import com.holdhundredyear.game.model.dto.MarketStateDTO;
import com.holdhundredyear.game.model.dto.StockQuoteDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 市场查询聚合服务集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖 Task 10.3 REST 收尾的查询侧契约：state 聚合字段、startDay 幂等后的盘中状态、
 * skip 收官日 summary 与按日查询一致性、daily-summary 默认最近结算日、排行榜
 * （8 NPC + 玩家共 9 行按资产降序）、资产曲线（day0 基期 10 万/1000 点）、
 * 无快照时 NOT_FOUND。
 */
@SpringBootTest
@Transactional
class MarketQueryServiceTest {

    private static final String BCRYPT_HASH = "$2a$10$bcrypthash";

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private FastForwardService fastForwardService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private MarketQueryService marketQueryService;

    @Autowired
    private WorldTickService worldTickService;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserDailySnapshotMapper userDailySnapshotMapper;

    @Autowired
    private UserStockStateMapper userStockStateMapper;

    @Test
    void countdownTicksAreTheRemainingTicksOfTheCurrentSession() {
        // 早盘只有 45 跳：用全天 90 跳做基准会让早盘倒计时正好是午盘的两倍
        assertEquals(45, MarketQueryService.remainingTicksInSession(MarketState.MORNING_OPEN, 0));
        assertEquals(1, MarketQueryService.remainingTicksInSession(MarketState.MORNING_OPEN, 44));
        assertEquals(45, MarketQueryService.remainingTicksInSession(MarketState.AFTERNOON_OPEN, 45));
        assertEquals(1, MarketQueryService.remainingTicksInSession(MarketState.AFTERNOON_OPEN, 89));
        // 越界与收盘态一律夹到 0：前端倒计时靠它对齐，负值会显示成负时间
        assertEquals(0, MarketQueryService.remainingTicksInSession(MarketState.MORNING_OPEN, 90));
        assertEquals(0, MarketQueryService.remainingTicksInSession(MarketState.AFTERNOON_OPEN, 120));
        assertEquals(0, MarketQueryService.remainingTicksInSession(MarketState.CLOSED, 0));
        assertEquals(0, MarketQueryService.remainingTicksInSession(MarketState.LUNCH_BREAK, 45));
    }


    @Test
    void stateReportsFreshWorldContract() {
        long userId = worldSeeder.seedUser("mq_state1", BCRYPT_HASH);

        MarketStateDTO state = marketQueryService.state(userId);

        assertEquals("NOT_STARTED", state.getMarketState());
        assertEquals(1, state.getCurrentDay());
        assertEquals(1, state.getYear());
        assertEquals(1, state.getDayInYear());
        assertNotNull(state.getEraCode(), "day 1 must belong to a seeded era");
        assertNotNull(state.getEraName());
        assertEquals("RETAIL", state.getTier());
        assertEquals(0, state.getTickIndex());
        assertEquals(0, state.getCountdownTicks(), "not trading means no countdown");
        assertNotNull(state.getWorldSeed());
        assertTrue(Long.parseLong(state.getWorldSeed()) > 0,
                "seed must be delivered as a precision-safe numeric string");
    }

    @Test
    void startDayIsIdempotentAndStateShowsMorningOpen() {
        long userId = worldSeeder.seedUser("mq_start1", BCRYPT_HASH);

        marketStateService.startDay(userId);
        marketStateService.startDay(userId);

        MarketStateDTO state = marketQueryService.state(userId);
        assertEquals("MORNING_OPEN", state.getMarketState(), "second startDay must be a no-op");
        assertEquals(1, state.getCurrentDay());
        assertEquals(0, state.getTickIndex());
        // 早盘只有 45 跳：倒计时必须是"本会话剩余跳数"，用全天 90 跳会让早盘看起来比午盘长一倍
        assertEquals(45, state.getCountdownTicks(), "morning countdown must be the 45 ticks left in that session");
    }

    @Test
    void limitPricesInEveryQuoteRowFollowTheRunGameMode() {
        long standard = worldSeeder.seedUser("mq_limit_std", BCRYPT_HASH);
        marketStateService.startDay(standard);
        StockQuoteDTO std = marketQueryService.stocksWithQuotes(standard).get(0);
        assertEquals(round2(std.getPrevClose() * 1.10), std.getLimitUp(), 0.011,
                "standard mode must publish +10% limit price");
        assertEquals(round2(std.getPrevClose() * 0.90), std.getLimitDown(), 0.011,
                "standard mode must publish -10% limit price");

        long quick = worldSeeder.seedUser("mq_limit_quick", BCRYPT_HASH, GameMode.QUICK);
        marketStateService.startDay(quick);
        StockQuoteDTO q = marketQueryService.stocksWithQuotes(quick).get(0);
        assertEquals(round2(q.getPrevClose() * 1.20), q.getLimitUp(), 0.011,
                "quick mode must publish +20% limit price, not the frontend's hardcoded 10%");
        assertEquals(round2(q.getPrevClose() * 0.80), q.getLimitDown(), 0.011,
                "quick mode must publish -20% limit price");
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0; // 除数必须是 100.0：Math.round 返回 long，/100 会做整数除法
    }

    @Test
    void stockListMirrorsBoardQuotesAndStaticDefs() {
        long userId = worldSeeder.seedUser("mq_stocks1", BCRYPT_HASH);
        marketStateService.startDay(userId);

        List<StockQuoteDTO> rows = marketQueryService.stocksWithQuotes(userId);

        assertEquals(StockDefs.ALL_INSTRUMENTS.size(), rows.size(), "24 只股票 + 3 只 ETF 都要在列表里");
        StockQuoteDTO etfRow = rows.stream().filter(StockQuoteDTO::isEtf).findFirst().orElseThrow();
        assertEquals(0.0, etfRow.getLimitUp(), 1e-9,
                "ETF 不下发涨停价（前端据此隐藏涨跌停、不拦单）");
        assertEquals(0.0, etfRow.getLimitDown(), 1e-9, "ETF 不下发跌停价");
        StockQuoteDTO first = rows.get(0);
        assertEquals(1L, first.getStockId());
        assertEquals("600001", first.getCode());
        assertEquals("贵州茅王", first.getName());
        assertEquals("白酒", first.getSector());
        assertEquals(168.00, first.getBasePrice(), 1e-9);
        assertEquals(168.00, first.getPrevClose(), 1e-9);
        assertEquals(168.00, first.getCurrent(), 1e-9, "fresh world trades at base price");
        assertEquals(0.0, first.getChangePct(), 1e-9, "changePct is a ratio (0.04 = +4%)");
    }

    @Test
    void stockIntradayAndIndexPointsGrowWithTicks() {
        long userId = worldSeeder.seedUser("mq_pts1", BCRYPT_HASH);
        marketStateService.startDay(userId);
        worldTickService.tickOne(userId);
        worldTickService.tickOne(userId);
        worldTickService.tickOne(userId);

        List<IntradayPointDTO> stockPoints = marketQueryService.stockIntraday(userId, 1L);
        List<IndexPointDTO> indexPoints = marketQueryService.indexIntraday(userId);

        assertEquals(90, stockPoints.size(), "fixed 90-slot intraday axis");
        assertEquals("09:30", stockPoints.get(0).getTime(), "first tick labels as morning open");
        assertTrue(stockPoints.get(0).getPrice() > 0, "price must be positive");
        assertNull(stockPoints.get(3).getPrice(), "ticks not yet reached must be null");
        assertNull(stockPoints.get(89).getPrice());
        assertEquals(90, indexPoints.size());
        assertTrue(indexPoints.get(0).getValue() > 0, "index value must be positive");
        assertNull(indexPoints.get(3).getValue());
    }

    @Test
    void intradayAxisLabelsPinSessionBoundaries() {
        long userId = worldSeeder.seedUser("mq_axis1", BCRYPT_HASH);
        marketStateService.startDay(userId);
        worldTickService.tickOne(userId);

        List<IntradayPointDTO> stockPoints = marketQueryService.stockIntraday(userId, 1L);

        assertEquals("09:30", stockPoints.get(0).getTime());
        assertEquals("11:30", stockPoints.get(44).getTime(), "morning session ends 11:30");
        assertEquals("13:00", stockPoints.get(45).getTime(), "afternoon session starts 13:00");
        assertEquals("15:00", stockPoints.get(89).getTime(), "afternoon session ends 15:00");
    }

    @Test
    void closedDayIntradayShowsFullNinetyPoints() {
        long userId = worldSeeder.seedUser("mq_pts2", BCRYPT_HASH);
        marketStateService.startDay(userId);
        fastForwardService.skip(userId);

        List<IntradayPointDTO> stockPoints = marketQueryService.stockIntraday(userId, 1L);
        List<IndexPointDTO> indexPoints = marketQueryService.indexIntraday(userId);

        assertEquals(90, stockPoints.size());
        for (IntradayPointDTO point : stockPoints) {
            assertNotNull(point.getPrice(), "closed day must show a complete curve, slot " + point.getTime());
        }
        assertEquals(90, indexPoints.size());
        assertNotNull(indexPoints.get(89).getValue());
    }

    @Test
    void stockIntradayOfUnknownStockThrowsNotFound() {
        long userId = worldSeeder.seedUser("mq_pts2", BCRYPT_HASH);
        marketStateService.startDay(userId);

        assertThrows(BizException.class, () -> marketQueryService.stockIntraday(userId, 999L));
    }

    @Test
    void dailySummaryDefaultsToLatestSettledDay() {
        long userId = worldSeeder.seedUser("mq_sum1", BCRYPT_HASH);
        settleDay(userId, 1, 1005.0);
        settleDay(userId, 2, 1010.0);

        DailySummaryDTO latest = marketQueryService.dailySummary(userId, null);

        assertEquals(2, latest.getGameDay(), "no gameDay means the latest settled day");
        assertEquals(1, marketQueryService.dailySummary(userId, 1).getGameDay());
    }

    @Test
    void skipProducesSummaryConsistentWithDailySummary() {
        long userId = worldSeeder.seedUser("mq_skip1", BCRYPT_HASH);
        marketStateService.startDay(userId);
        fastForwardService.skip(userId);

        DailySummaryDTO summary = marketQueryService.dailySummary(userId, null);

        assertEquals(1, summary.getGameDay(), "skip must land on the just-closed day");
        assertEquals(marketQueryService.dailySummary(userId, 1), summary,
                "skip summary must equal the explicit gameDay query");
        assertNotNull(summary.getNews());
        assertNotNull(summary.getNotices());
        assertTrue(summary.getNotices().contains("收盘结算"), "settlement notice must appear");
        assertNotNull(summary.getComment());
        assertTrue(!summary.getComment().isBlank(), "comment must be a non-blank meme line");
        assertEquals(0.0, summary.getGuardRatio(), 1e-9, "no debt means guardRatio 0");
        assertTrue(summary.getNetAsset() > 0);
    }

    @Test
    void leaderboardRanksNineRowsByAssetDescending() {
        long userId = worldSeeder.seedUser("mq_rank1", BCRYPT_HASH);
        settleDay(userId, 1, 1005.0);

        LeaderboardDTO leaderboard = marketQueryService.leaderboard(userId, null);

        assertEquals(1, leaderboard.getGameDay());
        List<LeaderboardDTO.Row> rows = leaderboard.getRows();
        assertEquals(9, rows.size(), "8 NPCs plus the player");
        assertEquals(8, rows.stream().filter(row -> !row.getIsPlayer()).count());
        LeaderboardDTO.Row player = rows.stream()
                .filter(LeaderboardDTO.Row::getIsPlayer)
                .findFirst().orElseThrow(() -> new AssertionError("player row missing"));
        assertEquals("您", player.getName());
        assertEquals("ACTIVE", player.getStatus(), "survived day is still ACTIVE");
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).getAsset() >= rows.get(i).getAsset(),
                    "rows must be sorted by asset descending");
        }
    }

    @Test
    void equityCurveCoversDayZeroToLatestSettledDay() {
        long userId = worldSeeder.seedUser("mq_curve1", BCRYPT_HASH);
        settleDay(userId, 1, 1005.0);
        settleDay(userId, 2, 1010.0);

        EquityCurveDTO curve = marketQueryService.equityCurve(userId);

        assertEquals(3, curve.getPoints().size(), "day 0..2");
        EquityCurveDTO.Point day0 = curve.getPoints().get(0);
        assertEquals(0, day0.getGameDay());
        assertEquals(100000.0, day0.getNetAsset(), 1e-6, "day 0 net asset is the initial balance");
        assertEquals(1000.0, day0.getIndexValue(), 1e-9, "day 0 index is the base index");
        assertEquals(2, curve.getPoints().get(2).getGameDay());
    }

    @Test
    void queriesWithoutSnapshotsThrowNotFound() {
        long userId = worldSeeder.seedUser("mq_none1", BCRYPT_HASH);
        userDailySnapshotMapper.delete(new LambdaQueryWrapper<UserDailySnapshot>()
                .eq(UserDailySnapshot::getUserId, userId));

        BizException summaryError = assertThrows(BizException.class,
                () -> marketQueryService.dailySummary(userId, null));
        assertEquals(ErrorCode.NOT_FOUND, summaryError.getErrorCode());
        BizException leaderboardError = assertThrows(BizException.class,
                () -> marketQueryService.leaderboard(userId, null));
        assertEquals(ErrorCode.NOT_FOUND, leaderboardError.getErrorCode());
        BizException curveError = assertThrows(BizException.class,
                () -> marketQueryService.equityCurve(userId));
        assertEquals(ErrorCode.NOT_FOUND, curveError.getErrorCode());
    }

    @Test
    void stockListUsesLastClosePctAfterSettlement() {
        long userId = worldSeeder.seedUser("mq_daypct1", BCRYPT_HASH);
        UserGameState state = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        state.setMarketState("SETTLING");
        userGameStateMapper.updateById(state);
        UserStockState stock = userStockStateMapper.selectOne(
                new LambdaQueryWrapper<UserStockState>()
                        .eq(UserStockState::getUserId, userId)
                        .eq(UserStockState::getStockId, 1L));
        stock.setCurrentPrice(new BigDecimal("170.00"));
        userStockStateMapper.updateById(stock);

        settlementService.settle(userId);

        List<StockQuoteDTO> rows = marketQueryService.stocksWithQuotes(userId);
        StockQuoteDTO first = rows.get(0);
        assertEquals(1L, first.getStockId());
        assertEquals((170.00 - 168.00) / 168.00, first.getChangePct(), 1e-9,
                "收盘态 changePct 必须来自持久化的当日涨跌幅而非恒 0 的 dayChangePct()");
    }

    @Test
    void stockListShowsClosedDayOhlcFromDailyBar() {
        long userId = worldSeeder.seedUser("mq_ohlc1", BCRYPT_HASH);
        UserGameState state = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        state.setMarketState("SETTLING");
        userGameStateMapper.updateById(state);
        UserStockState stock = userStockStateMapper.selectOne(
                new LambdaQueryWrapper<UserStockState>()
                        .eq(UserStockState::getUserId, userId)
                        .eq(UserStockState::getStockId, 1L));
        // 当日日内 OHLC：开 169 / 高 172 / 低 167.5 / 收 170（prev_close 168）
        stock.setCurrentPrice(new BigDecimal("170.00"));
        stock.setOpenPrice(new BigDecimal("169.00"));
        stock.setHighPrice(new BigDecimal("172.00"));
        stock.setLowPrice(new BigDecimal("167.50"));
        userStockStateMapper.updateById(stock);

        settlementService.settle(userId);

        StockQuoteDTO first = marketQueryService.stocksWithQuotes(userId).get(0);
        assertEquals(169.00, first.getOpen(), 1e-9, "收盘态今开取当日 bar");
        assertEquals(172.00, first.getHigh(), 1e-9, "收盘态最高取当日 bar（结算已重置 state 的 OHL）");
        assertEquals(167.50, first.getLow(), 1e-9);
        assertEquals(170.00, first.getCurrent(), 1e-9);
        assertEquals(168.00, first.getPrevClose(), 1e-9,
                "收盘态昨收由 bar 涨跌幅反推（prev_close 列已被双写为收盘价）");
    }

    @Test
    void klineReturnsBarsAscendingCappedByLimit() {
        long userId = worldSeeder.seedUser("mq_kline1", BCRYPT_HASH);
        settleDay(userId, 1, 1002.0);
        settleDay(userId, 2, 1004.0);
        settleDay(userId, 3, 1006.0);

        // 开局前的历史 K 排在最前（game_day <= 0），结算日接在后面
        List<KlinePointDTO> full = marketQueryService.stockKline(userId, 1L, 60);
        assertEquals(PreHistoryDefs.BARS + 3, full.size(), "pre-history plus three settled days");
        assertEquals(1 - PreHistoryDefs.BARS, full.get(0).getGameDay(), "oldest bar is the first pre-history day");
        assertEquals(0, full.get(PreHistoryDefs.BARS - 1).getGameDay(), "pre-history ends at day 0");
        assertEquals(1, full.get(PreHistoryDefs.BARS).getGameDay(), "bars must be ascending by game_day");
        assertEquals(3, full.get(full.size() - 1).getGameDay());

        List<KlinePointDTO> window = marketQueryService.stockKline(userId, 1L, 2);
        assertEquals(2, window.size(), "limit caps to the latest bars");
        assertEquals(2, window.get(0).getGameDay());
        assertEquals(3, window.get(1).getGameDay());
        assertEquals(168.00, window.get(0).getClose(), 1e-9,
                "seeded world trades at base price, so close is base_price");
        assertEquals(0.0, window.get(0).getChangePct(), 1e-9);
    }

    /**
     * 除权日的日K必须连续（回归玩家报的"图上腰斩、鼠标却只写 −10%"）：三日剧本 ——
     * 第 1 日正常收 170（昨收 168）；第 2 日盘中 10转10，prev_close 已折算到 85 口径、收 84.15；
     * 第 3 日 +5% 收 88.36。库里第 1 日存的是**当日真实成交价** 170（历史价不该被后来的公司
     * 行动改写），下发给图的序列要把它压到 85 口径，于是相邻两根的高度差恰好等于 tooltip
     * 里那根的真实涨跌幅，不再出现 50% 的假断崖。
     */
    @Test
    void klineIsForwardAdjustedAcrossExRightsSoChartMatchesTooltip() {
        long userId = worldSeeder.seedUser("mq_adj1", BCRYPT_HASH);
        settleDayWithOhlc(userId, 1, 168.00, 170.00, 169.00, 172.00, 167.50);
        settleDayWithOhlc(userId, 2, 85.00, 84.15, 84.50, 86.00, 84.00);
        settleDayWithOhlc(userId, 3, 84.15, 88.36, 84.20, 89.00, 84.00);

        List<KlinePointDTO> bars = marketQueryService.stockKline(userId, 1L, 60);
        int n = bars.size();
        KlinePointDTO day1 = bars.get(n - 3);
        KlinePointDTO day2 = bars.get(n - 2);
        KlinePointDTO day3 = bars.get(n - 1);

        assertEquals(88.36, day3.getClose(), 0.005,
                "最新一根锚定真实成交价——右下角的当前价不能被复权改掉");
        assertEquals(85.00, day1.getClose(), 0.005,
                "除权前的收盘要按前复权压到当前口径（170 → 85），不能留 50% 假断崖");
        assertEquals(84.50, day1.getOpen(), 0.005, "open/high/low 与 close 同因子折算");
        assertEquals(86.00, day1.getHigh(), 0.005);
        assertEquals(83.75, day1.getLow(), 0.005);
        assertEquals(-0.01, day2.getChangePct(), 1e-6, "涨跌幅不受复权影响，仍是当日真实 −1%");

        for (int i = n - 2; i < n; i++) {
            double jump = bars.get(i).getClose() / bars.get(i - 1).getClose() - 1;
            assertEquals(bars.get(i).getChangePct(), jump, 0.005,
                    "第 " + bars.get(i).getGameDay() + " 根的图内跳幅必须与 tooltip 涨跌幅一致");
        }
    }

    @Test
    void klineOfUnknownStockThrowsNotFound() {
        long userId = worldSeeder.seedUser("mq_kline2", BCRYPT_HASH);
        assertThrows(BizException.class, () -> marketQueryService.stockKline(userId, 999L, 60));
    }

    @Test
    void stateExposesIndexValueAndDayPctForStatusBar() {
        long userId = worldSeeder.seedUser("mq_idxpct1", BCRYPT_HASH);
        // 未开盘：指数 1000 基期，涨跌幅 0（day0 快照 indexValue=1000 为昨收）
        MarketStateDTO fresh = marketQueryService.state(userId);
        assertEquals(1000.0, fresh.getIndexValue(), 1e-9);
        assertEquals(0.0, fresh.getIndexDayPct(), 1e-9);

        // 结算第 1 日指数 1005 → 收盘态当日涨跌幅 +0.5%
        settleDay(userId, 1, 1005.0);
        MarketStateDTO closed = marketQueryService.state(userId);
        assertEquals(1005.0, closed.getIndexValue(), 1e-9);
        assertEquals(0.5, closed.getIndexDayPct(), 1e-9);
    }

    /** 结算整日并指定 1 号股的当日口径（昨收/收盘/开高低），用于精确构造日K剧本。 */
    private void settleDayWithOhlc(long userId, int day, double prevClose, double close,
                                   double open, double high, double low) {
        UserGameState state = gameState(userId);
        state.setCurrentDay(day);
        state.setMarketState("SETTLING");
        state.setIndexValue(1000.0);
        userGameStateMapper.updateById(state);
        UserStockState stock = userStockStateMapper.selectOne(
                new LambdaQueryWrapper<UserStockState>()
                        .eq(UserStockState::getUserId, userId)
                        .eq(UserStockState::getStockId, 1L));
        stock.setPrevClose(BigDecimal.valueOf(prevClose));
        stock.setCurrentPrice(BigDecimal.valueOf(close));
        stock.setOpenPrice(BigDecimal.valueOf(open));
        stock.setHighPrice(BigDecimal.valueOf(high));
        stock.setLowPrice(BigDecimal.valueOf(low));
        userStockStateMapper.updateById(stock);
        settlementService.settle(userId);
    }

    /** 置为待结算上下文并结算该日（fixture 同 SettlementServiceTest）。 */
    private void settleDay(long userId, int day, double closeIndex) {
        UserGameState state = gameState(userId);
        state.setCurrentDay(day);
        state.setMarketState("SETTLING");
        state.setIndexValue(closeIndex);
        userGameStateMapper.updateById(state);
        settlementService.settle(userId);
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }
}
