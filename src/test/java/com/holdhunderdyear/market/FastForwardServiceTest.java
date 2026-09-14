package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.market.entity.StockDailyBar;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserEra;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserScriptDay;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.StockDailyBarMapper;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserEraMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserScriptDayMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快进服务集成测试（hold_year_test 库）。skip 与手动逐跳同为单线程全程持世界锁推进，
 * 与 {@code @Transactional} 测试事务兼容（tickOne/startDay 的 TransactionTemplate 加入
 * 测试事务，结束回滚，无需清理）。覆盖：盘中 skip 直接收官、CLOSED 态 skip 只开一日、
 * NOT_STARTED 直接收官第 1 日、第 108 天收官 FINISHED、熔断日提前收盘，以及同 seed 下
 * skip 与手动逐跳的世界等价性（快进不得引入任何随机性偏差）。
 */
@SpringBootTest
@Transactional
class FastForwardServiceTest {

    @Autowired private WorldSeeder worldSeeder;
    @Autowired private MarketStateService marketStateService;
    @Autowired private WorldTickService worldTickService;
    @Autowired private FastForwardService fastForwardService;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserStockStateMapper userStockStateMapper;
    @Autowired private UserMessageMapper userMessageMapper;
    @Autowired private UserDailySnapshotMapper userDailySnapshotMapper;
    @Autowired private StockDailyBarMapper stockDailyBarMapper;
    @Autowired private UserEraMapper userEraMapper;
    @Autowired private UserScriptDayMapper userScriptDayMapper;

    @Test
    void skipRunsIntradayDayStraightToClose() {
        long userId = worldSeeder.seedUser("ff_intraday1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 20260904L);
        marketStateService.startDay(userId);

        fastForwardService.skip(userId);

        UserGameState after = gameState(userId);
        assertEquals("CLOSED", after.getMarketState(), "skip must run the trading day to close");
        assertEquals(1, after.getCurrentDay(), "intraday skip must not roll over to a next day");
        assertEquals(90, after.getTickIndex(), "a full trading day is exactly 90 ticks");
        assertEquals(90, quoteBoardRegistry.getOrLoad(userId).getTickIndex(),
                "board tick count must match the persisted checkpoint");
    }

    @Test
    void skipFromClosedOpensExactlyOneDayAndClosesIt() {
        long userId = worldSeeder.seedUser("ff_closed1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 20260904L);
        UserGameState gs = gameState(userId);
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);

        fastForwardService.skip(userId);

        UserGameState after = gameState(userId);
        assertEquals(2, after.getCurrentDay(), "one skip must open exactly one next day");
        assertEquals("CLOSED", after.getMarketState(), "the opened day must also be closed by the same skip");
        assertTrue(after.getTickIndex() >= 1 && after.getTickIndex() <= 90,
                "next day must be played (circuit-breaker early close allowed): " + after.getTickIndex());
    }

    @Test
    void skipFromNotStartedPlaysFirstDayToClose() {
        long userId = worldSeeder.seedUser("ff_notstart1", "$2a$10$bcrypthash");

        fastForwardService.skip(userId);

        UserGameState after = gameState(userId);
        assertEquals(1, after.getCurrentDay(), "first skip from a fresh world plays day 1");
        assertEquals("CLOSED", after.getMarketState(), "the opened day must be closed by the same skip");
        assertTrue(after.getTickIndex() >= 1, "day 1 must be played, not skipped over");
    }

    @Test
    void skipOnFinalDayFinishesTheGame() {
        long userId = worldSeeder.seedUser("ff_final1", "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(107);
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);

        fastForwardService.skip(userId);

        UserGameState after = gameState(userId);
        assertEquals("FINISHED", after.getMarketState(), "closing day 108 must finish the game");
        assertEquals("NORMAL_FINISH", after.getEndReason(), "surviving to day 108 is a normal finish");
        assertEquals(108, after.getEndDay(), "finish day must record the final trading day");
    }

    @Test
    void skipOnCircuitBreakerDayClosesEarly() {
        long userId = worldSeeder.seedUser("ff_cb1", "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(36);
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);

        fastForwardService.skip(userId);

        UserGameState after = gameState(userId);
        assertEquals(37, after.getCurrentDay(), "era5 first day is 37");
        assertEquals("CLOSED", after.getMarketState(), "circuit-breaker day must still settle to closed");
        assertTrue(after.getTickIndex() < 90, "circuit-breaker early close must stop before full 90 ticks");
        assertTrue(quoteBoardRegistry.getOrLoad(userId).getTickIndex() < 90,
                "board tick count must reflect the early close");
    }

    @Test
    void skipReproducesTheSameWorldAsManualTicking() {
        long fixedSeed = 20260904L;
        long userIdA = seedWithFixedSeed("ff_eqa1", fixedSeed);
        long userIdB = seedWithFixedSeed("ff_eqb1", fixedSeed);

        marketStateService.startDay(userIdA);
        for (int i = 0; i < 45; i++) {
            worldTickService.tickOne(userIdA);
        }
        if ("LUNCH_BREAK".equals(gameState(userIdA).getMarketState())) {
            marketStateService.resumeAfternoon(userIdA);
        }
        for (int i = 0; i < 45; i++) {
            worldTickService.tickOne(userIdA);
        }

        marketStateService.startDay(userIdB);
        fastForwardService.skip(userIdB);

        for (long stockId = 1; stockId <= 24; stockId++) {
            UserStockState a = stockState(userIdA, stockId);
            UserStockState b = stockState(userIdB, stockId);
            assertEquals(0, a.getOpenPrice().compareTo(b.getOpenPrice()),
                    "stock " + stockId + " open price must be identical");
            assertEquals(0, a.getHighPrice().compareTo(b.getHighPrice()),
                    "stock " + stockId + " high price must be identical");
            assertEquals(0, a.getLowPrice().compareTo(b.getLowPrice()),
                    "stock " + stockId + " low price must be identical");
            assertEquals(0, a.getCurrentPrice().compareTo(b.getCurrentPrice()),
                    "stock " + stockId + " close price must be identical");
        }
        UserGameState stateA = gameState(userIdA);
        UserGameState stateB = gameState(userIdB);
        assertEquals(stateA.getIndexValue(), stateB.getIndexValue(), 0.0, "index value must be bit-identical");
        assertEquals(stateA.getTickIndex(), stateB.getTickIndex(), "tick count must match");
        assertEquals(stateA.getMarketState(), stateB.getMarketState(), "market state must match");
        assertEquals(messagesOf(userIdA), messagesOf(userIdB), "same seed worlds must deliver the same messages");
        assertEquals(snapshotsOf(userIdA), snapshotsOf(userIdB),
                "same seed worlds must settle to identical daily snapshots");
    }

    @Test
    void skipWritesTheSameDailyBarsAsManualTicking() {
        long fixedSeed = 20260904L;
        long userIdA = seedWithFixedSeed("ff_bara1", fixedSeed);
        long userIdB = seedWithFixedSeed("ff_barb1", fixedSeed);

        marketStateService.startDay(userIdA);
        for (int i = 0; i < 45; i++) {
            worldTickService.tickOne(userIdA);
        }
        if ("LUNCH_BREAK".equals(gameState(userIdA).getMarketState())) {
            marketStateService.resumeAfternoon(userIdA);
        }
        for (int i = 0; i < 45; i++) {
            worldTickService.tickOne(userIdA);
        }

        marketStateService.startDay(userIdB);
        fastForwardService.skip(userIdB);

        for (long stockId = 1; stockId <= 24; stockId++) {
            StockDailyBar barA = stockDailyBarMapper.selectOne(new LambdaQueryWrapper<StockDailyBar>()
                    .eq(StockDailyBar::getUserId, userIdA)
                    .eq(StockDailyBar::getStockId, stockId)
                    .eq(StockDailyBar::getGameDay, 1));
            StockDailyBar barB = stockDailyBarMapper.selectOne(new LambdaQueryWrapper<StockDailyBar>()
                    .eq(StockDailyBar::getUserId, userIdB)
                    .eq(StockDailyBar::getStockId, stockId)
                    .eq(StockDailyBar::getGameDay, 1));
            assertTrue(barA != null && barB != null, "stock " + stockId + " bar must exist on both paths");
            assertEquals(0, barA.getOpen().compareTo(barB.getOpen()), "stock " + stockId + " bar open");
            assertEquals(0, barA.getHigh().compareTo(barB.getHigh()), "stock " + stockId + " bar high");
            assertEquals(0, barA.getLow().compareTo(barB.getLow()), "stock " + stockId + " bar low");
            assertEquals(0, barA.getClose().compareTo(barB.getClose()), "stock " + stockId + " bar close");
            assertEquals(barA.getChangePct(), barB.getChangePct(), "stock " + stockId + " bar changePct");
        }
    }

    /**
     * 用固定种子重播种一个用户的完整世界：seedUser 以随机种子建号（其日程随机）；
     * 等价性对比要求两用户的世界（含脚本日/IPO 日程）完全一致，故删除该用户的随机种子
     * 游戏数据后从固定种子重建（复用生产播种器 seedWorld），再同步 game_state.world_seed。
     */
    private long seedWithFixedSeed(String username, long worldSeed) {
        long userId = worldSeeder.seedUser(username, "$2a$10$bcrypthash");
        userStockStateMapper.delete(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId));
        userEraMapper.delete(new LambdaQueryWrapper<UserEra>()
                .eq(UserEra::getUserId, userId));
        userScriptDayMapper.delete(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId));
        userDailySnapshotMapper.delete(new LambdaQueryWrapper<UserDailySnapshot>()
                .eq(UserDailySnapshot::getUserId, userId));
        worldSeeder.seedWorld(userId, worldSeed);
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(worldSeed);
        userGameStateMapper.updateById(gs);
        return userId;
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

    private List<String> messagesOf(long userId) {
        List<UserMessage> messages = userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
        List<String> keys = new ArrayList<>();
        for (UserMessage message : messages) {
            keys.add(String.join("|", message.getSource(), message.getType(),
                    String.valueOf(message.getGameDay()), message.getTitle(), message.getContent()));
        }
        keys.sort(String::compareTo);
        return keys;
    }

    private List<String> snapshotsOf(long userId) {
        List<UserDailySnapshot> snapshots = userDailySnapshotMapper.selectList(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId));
        List<String> keys = new ArrayList<>();
        for (UserDailySnapshot snapshot : snapshots) {
            keys.add(String.join("|",
                    String.valueOf(snapshot.getGameDay()),
                    String.valueOf(snapshot.getCash()),
                    String.valueOf(snapshot.getMarketValue()),
                    String.valueOf(snapshot.getDebt()),
                    String.valueOf(snapshot.getNetAsset()),
                    String.valueOf(snapshot.getDayReturnPct()),
                    String.valueOf(snapshot.getIndexValue()),
                    String.valueOf(snapshot.getIndexDayPct()),
                    String.valueOf(snapshot.getNpcAssetsJson())));
        }
        keys.sort(String::compareTo);
        return keys;
    }
}
