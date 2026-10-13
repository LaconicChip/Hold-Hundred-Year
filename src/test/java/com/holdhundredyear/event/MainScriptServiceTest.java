package com.holdhundredyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.event.MainScriptService.ScriptKind;
import com.holdhundredyear.market.FastForwardService;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.WorldTickService;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.entity.StockDailyBar;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserEraMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.market.mapper.StockDailyBarMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主力剧本服务集成测试（hold_year_test 库，方法级事务自动回滚）：
 * 三剧本完成语义（洗盘创新高 / 出货破位 / 天地板单跳到跌停）、每用户单活跃剧本、
 * 同 seed 下逐跳推进与快进的世界等价性（P5 快进等价，剧本全部参数启动时一次掷出）。
 * 剧本经 package-private 探针 startFor/getState 可控触发（自然启动耦合单据置困难）。
 */
@SpringBootTest
@Transactional
class MainScriptServiceTest {

    @Autowired private MainScriptService mainScriptService;
    @Autowired private WorldSeeder worldSeeder;
    @Autowired private MarketStateService marketStateService;
    @Autowired private WorldTickService worldTickService;
    @Autowired private FastForwardService fastForwardService;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserMessageMapper userMessageMapper;
    @Autowired private UserStockStateMapper userStockStateMapper;
    @Autowired private UserEraMapper userEraMapper;
    @Autowired private UserScriptDayMapper userScriptDayMapper;
    @Autowired private UserDailySnapshotMapper userDailySnapshotMapper;
    @Autowired private StockDailyBarMapper stockDailyBarMapper;

    @Test
    void shakeoutCompletesToNewHigh() {
        long userId = worldSeeder.seedUser("ms_shake1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 20260905L);
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        long stockId = 1L;

        boolean started = mainScriptService.startFor(userId, gameState(userId), board,
                new Random(42), ScriptKind.SHAKEOUT, stockId);
        assertTrue(started, "SHAKEOUT must start on request");
        double priorPeak = mainScriptService.getState(userId).priorPeak;

        int guard = 0;
        while (!mainScriptService.getState(userId).isDone() && guard < 600) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
            guard++;
        }
        assertTrue(mainScriptService.getState(userId).isDone(),
                "SHAKEOUT script must complete, guard=" + guard);
        double current = quoteBoardRegistry.getOrLoad(userId).quote(stockId).getCurrent();
        assertTrue(current > priorPeak,
                "SHAKEOUT must innovate a new high: current=" + current + " priorPeak=" + priorPeak);
    }

    @Test
    void pumpDumpCompletesToBreakdown() {
        long userId = worldSeeder.seedUser("ms_pump1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 3141592653L);
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        long stockId = 7L;

        boolean started = mainScriptService.startFor(userId, gameState(userId), board,
                new Random(5), ScriptKind.PUMP_DUMP, stockId);
        assertTrue(started, "PUMP_DUMP must start on request");
        double launchPrice = mainScriptService.getState(userId).launchPrice;

        int guard = 0;
        while (!mainScriptService.getState(userId).isDone() && guard < 600) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
            guard++;
        }
        assertTrue(mainScriptService.getState(userId).isDone(),
                "PUMP_DUMP script must complete, guard=" + guard);
        double current = quoteBoardRegistry.getOrLoad(userId).quote(stockId).getCurrent();
        assertTrue(current < launchPrice,
                "PUMP_DUMP must break below launch price: current=" + current + " launch=" + launchPrice);
    }

    @Test
    void skyEarthDropsFromLimitUpToLimitDownInOneTick() {
        long userId = worldSeeder.seedUser("ms_sky1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 2718281828L);
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        long stockId = 1L;
        Quote q = board.quote(stockId);
        q.setCurrent(q.limitUpPrice());

        boolean started = mainScriptService.startFor(userId, gameState(userId), board,
                new Random(7), ScriptKind.SKY_EARTH, stockId);
        assertTrue(started, "SKY_EARTH must start on request");
        assertTrue(mainScriptService.getState(userId).isDone(),
                "SKY_EARTH is a single-jump script and must be done immediately after start");

        worldTickService.tickOne(userId);
        handleBoundary(userId);

        Quote after = quoteBoardRegistry.getOrLoad(userId).quote(stockId);
        assertEquals(after.limitDownPrice(), after.getCurrent(), 0.0,
                "SKY_EARTH must drop to limit-down in a single tick");
    }

    @Test
    void onlyOneActiveScriptPerUser() {
        long userId = worldSeeder.seedUser("ms_onescript1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 1618033L);
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        assertTrue(mainScriptService.startFor(userId, gameState(userId), board,
                new Random(1), ScriptKind.PUMP_DUMP, 1L),
                "first script must start");
        assertFalse(mainScriptService.maybeStart(userId, gameState(userId), board, new Random(2)),
                "a second script must be refused while the first is still active");
        assertFalse(mainScriptService.getState(userId).isDone(),
                "the launched PUMP_DUMP must still be active (not done)");
    }

    @Test
    void fastForwardReproducesSameScriptWorldAsManualTicking() {
        long fixedSeed = 20260905L;
        long userIdA = seedWithFixedSeed("ms_ffa1", fixedSeed);
        long userIdB = seedWithFixedSeed("ms_ffb1", fixedSeed);
        marketStateService.startDay(userIdA);
        marketStateService.startDay(userIdB);

        // 同 seed 同参数强制同一剧本，把脚本路径纳入等价性（自然启动同 seed 亦等价）
        assertTrue(mainScriptService.startFor(userIdA, gameState(userIdA),
                quoteBoardRegistry.getOrLoad(userIdA), new Random(99), ScriptKind.SHAKEOUT, 1L));
        assertTrue(mainScriptService.startFor(userIdB, gameState(userIdB),
                quoteBoardRegistry.getOrLoad(userIdB), new Random(99), ScriptKind.SHAKEOUT, 1L));

        for (int i = 0; i < 45; i++) {
            worldTickService.tickOne(userIdA);
        }
        if ("LUNCH_BREAK".equals(gameState(userIdA).getMarketState())) {
            marketStateService.resumeAfternoon(userIdA);
        }
        for (int i = 0; i < 45; i++) {
            worldTickService.tickOne(userIdA);
        }

        fastForwardService.skip(userIdB);

        assertScriptMessagePresent(userIdA);
        assertScriptMessagePresent(userIdB);
        for (long stockId = 1; stockId <= 24; stockId++) {
            UserStockState a = stockState(userIdA, stockId);
            UserStockState b = stockState(userIdB, stockId);
            assertEquals(0, a.getOpenPrice().compareTo(b.getOpenPrice()),
                    "stock " + stockId + " open must be identical");
            assertEquals(0, a.getHighPrice().compareTo(b.getHighPrice()),
                    "stock " + stockId + " high must be identical");
            assertEquals(0, a.getLowPrice().compareTo(b.getLowPrice()),
                    "stock " + stockId + " low must be identical");
            assertEquals(0, a.getCurrentPrice().compareTo(b.getCurrentPrice()),
                    "stock " + stockId + " close must be identical");
        }
        UserGameState stateA = gameState(userIdA);
        UserGameState stateB = gameState(userIdB);
        assertEquals(stateA.getIndexValue(), stateB.getIndexValue(), 0.0,
                "index must be bit-identical");
        assertEquals(messagesOf(userIdA), messagesOf(userIdB),
                "same seed worlds must deliver the same messages (incl. script messages)");
    }

    // ---- 辅助 ----

    private void assertScriptMessagePresent(long userId) {
        boolean found = userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                        .eq(UserMessage::getUserId, userId))
                .stream().anyMatch(m -> m.getContent() != null && m.getContent().contains("主力"));
        assertTrue(found, "a main-force opening message must be present for user " + userId);
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

    /**
     * 用固定种子重播种一个用户的完整世界（删除 seedUser 随机种子生成的游戏数据后从固定
     * 种子重建）：快进等价性要求两用户世界（含脚本日/IPO 日程）完全一致，复用生产播种器。
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
        // seedWorld 会连开局前的历史日K一起铺，重播种前必须先清掉旧世界的日K
        stockDailyBarMapper.delete(new LambdaQueryWrapper<StockDailyBar>()
                .eq(StockDailyBar::getUserId, userId));
        worldSeeder.seedWorld(userId, worldSeed);
        setWorldSeed(userId, worldSeed);
        return userId;
    }

    private UserStockState stockState(long userId, long stockId) {
        return userStockStateMapper.selectOne(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId)
                .eq(UserStockState::getStockId, stockId));
    }

    private void handleBoundary(long userId) {
        UserGameState gs = gameState(userId);
        switch (gs.getMarketState()) {
            case "LUNCH_BREAK" -> marketStateService.resumeAfternoon(userId);
            case "CLOSED" -> marketStateService.startDay(userId);
            default -> { }
        }
    }

    private List<String> messagesOf(long userId) {
        List<UserMessage> messages = userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
        List<String> keys = new ArrayList<>();
        for (UserMessage message : messages) {
            keys.add(String.join("|", message.getSource(), message.getType(),
                    String.valueOf(message.getGameDay()), message.getTitle(),
                    String.valueOf(message.getContent())));
        }
        keys.sort(String::compareTo);
        return keys;
    }
}