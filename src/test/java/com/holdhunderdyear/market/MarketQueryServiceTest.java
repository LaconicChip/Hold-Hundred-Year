package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.game.model.dto.DailySummaryDTO;
import com.holdhunderdyear.game.model.dto.EquityCurveDTO;
import com.holdhunderdyear.game.model.dto.IndexPointDTO;
import com.holdhunderdyear.game.model.dto.IntradayPointDTO;
import com.holdhunderdyear.game.model.dto.LeaderboardDTO;
import com.holdhunderdyear.game.model.dto.MarketStateDTO;
import com.holdhunderdyear.game.model.dto.StockQuoteDTO;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.world.WorldSeeder;
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
        assertEquals(90, state.getCountdownTicks(), "intraday countdown is 90 - tickIndex");
    }

    @Test
    void stockListMirrorsBoardQuotesAndStaticDefs() {
        long userId = worldSeeder.seedUser("mq_stocks1", BCRYPT_HASH);
        marketStateService.startDay(userId);

        List<StockQuoteDTO> rows = marketQueryService.stocksWithQuotes(userId);

        assertEquals(24, rows.size(), "all 24 stocks must be listed");
        StockQuoteDTO first = rows.get(0);
        assertEquals(1L, first.getStockId());
        assertEquals("600001", first.getCode());
        assertEquals("鸱夷酒业", first.getName());
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
