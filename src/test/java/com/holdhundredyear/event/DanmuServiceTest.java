package com.holdhundredyear.event;

import com.holdhundredyear.game.constants.StockDefs.StockDef;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.content.DanmuTexts;
import com.holdhundredyear.game.content.StockPersonas;
import com.holdhundredyear.game.model.enums.SealState;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.WorldTickService;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldRandom;
import com.holdhundredyear.world.WorldSeeder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹幕服务集成测试（hold_year_test 库，方法级事务自动回滚）：pickDanmu 纯函数在各触发态
 * 下的分类命中（指数大跌/涨停封板/急拉/横盘/冷场不命中），以及 maybePush 软依赖投递
 * （不落 user_message、PushService 缺失时静默跳过）。
 */
@SpringBootTest
@Transactional
class DanmuServiceTest {

    @Autowired
    private DanmuService danmuService;

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private WorldTickService worldTickService;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    // ---- 纯函数触发判定 ----

    @Test
    void fullFlatBoardYieldsFlatDanmu() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1000.0);
        String danmu = DanmuService.pickDanmu(userId, board, new Random(7));
        assertNotNull(danmu, "a fully flat board must trigger the flat/quiet danmu class");
        assertTrue(DanmuTexts.FLAT.contains(danmu), "danmu must come from the flat category");
    }

    @Test
    void tinyMoveBelowThresholdYieldsNothing() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1000.0);
        // 某股 2% 变动：低于次级急动 (0.03) 且使整体不平静 → 应返回 null，避免每跳刷屏
        Quote q = board.quote(StockDefs.ALL.get(0).id());
        q.setCurrent(Quote.round2(q.getPrevClose() * 1.02));
        String danmu = DanmuService.pickDanmu(userId, board, new Random(7));
        assertNull(danmu, "a sub-threshold 2% move must yield no danmu");
    }

    @Test
    void indexBigDropYieldsIndexDropDanmu() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1000.0);
        board.setPrevIndex(1000.0);
        board.setIndexValue(970.0); // -3% ≤ -2% 触发指数大跌
        String danmu = DanmuService.pickDanmu(userId, board, new Random(7));
        assertNotNull(danmu, "index drop <=-2% must trigger the index-drop class");
        assertTrue(DanmuTexts.INDEX_DROP.contains(danmu), "danmu must come from the index-drop category");
    }

    @Test
    void limitUpSealYieldsLimitUpDanmu() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1000.0);
        Quote q = board.quote(StockDefs.ALL.get(0).id());
        q.setSealed(SealState.UP);
        q.setCurrent(q.limitUpPrice());
        String danmu = DanmuService.pickDanmu(userId, board, new Random(7));
        assertNotNull(danmu, "a limit-up sealed stock must trigger the limit-up class");
        assertTrue(inStockFlavor(danmu, StockDefs.ALL.get(0).id(), DanmuTexts.LIMIT_UP),
                "limit-up danmu must come from the stock/sector/global limit-up tiers");
    }

    @Test
    void bigSurgingStockYieldsRallyDanmu() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1000.0);
        Quote q = board.quote(StockDefs.ALL.get(0).id());
        q.setCurrent(Quote.round2(q.getPrevClose() * 1.08)); // +8% > 5% 触发急拉
        String danmu = DanmuService.pickDanmu(userId, board, new Random(7));
        assertNotNull(danmu, "a +8% surging stock must trigger the rally class");
        assertTrue(inStockFlavor(danmu, StockDefs.ALL.get(0).id(), DanmuTexts.RALLY),
                "rally danmu must come from the stock/sector/global rally tiers");
    }

    @Test
    void bigPlungingStockYieldsDiveDanmu() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1000.0);
        Quote q = board.quote(StockDefs.ALL.get(0).id());
        q.setCurrent(Quote.round2(q.getPrevClose() * 0.94)); // -6% > 5% 触发跳水
        String danmu = DanmuService.pickDanmu(userId, board, new Random(7));
        assertNotNull(danmu, "a -6% plunging stock must trigger the dive class");
        assertTrue(inStockFlavor(danmu, StockDefs.ALL.get(0).id(), DanmuTexts.DIVE),
                "dive danmu must come from the stock/sector/global dive tiers");
    }

    @Test
    void indexRallyYieldsIndexRiseDanmu() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1025.0); // 指数 +2.5%（prevIndex 由 flatBoard 置为同值，手动压低基准）
        board.setPrevIndex(1000.0);
        String danmu = DanmuService.pickDanmu(userId, board, new Random(3));
        assertNotNull(danmu, "index rally >= +2% must trigger the index-rise class");
        assertTrue(DanmuTexts.INDEX_RISE.contains(danmu), "index rally must read the global index-rise pool");
    }

    @Test
    void sealedStockEventuallyUsesItsOwnPersonaPool() {
        // 同一触发态换不同随机源：个股专属池必须真的会被抽到（否则"每只票专属文案"是空话）
        long stockId = StockDefs.ALL.get(0).id();
        List<String> persona = StockPersonas.byId(stockId).danmu();
        int hits = 0;
        for (int seed = 0; seed < 60; seed++) {
            QuoteBoard board = flatBoard(1000.0);
            Quote q = board.quote(stockId);
            q.setSealed(SealState.UP);
            q.setCurrent(q.limitUpPrice());
            // 用生产同款播种方式（WorldRandom 会先打散种子）：new Random(小整数) 的首个
            // double 分布偏斜，测不出三级池的真实占比。
            String danmu = DanmuService.pickDanmu(1L, board, WorldRandom.of(777L, "danmu" + seed));
            if (persona.contains(danmu)) {
                hits++;
            }
        }
        assertTrue(hits >= 10, "persona pool must show up often, got " + hits + "/60");
    }

    /** 个股级触发的三级池并集：该股专属 → 所属板块 → 全局类别。 */
    private static boolean inStockFlavor(String danmu, long stockId, List<String> globalPool) {
        return StockPersonas.byId(stockId).danmu().contains(danmu)
                || DanmuTexts.sectorPool(StockDefs.byId(stockId).sector()).contains(danmu)
                || globalPool.contains(danmu);
    }

    // ---- 节流：冷却与去重 ----

    /**
     * 节流三件事各验一条：任意两条至少隔 3 跳、同一类别至少隔 10 跳、
     * 与最近 8 条重复的文案直接不推。旧版每跳最多一条且同一句能连着来八遍，
     * 那就是"人机味"和"刷屏"的来源。
     */
    @Test
    void throttleBlocksTooCloseAndRepeatedLines() {
        long userId = 991001L;
        var flat = new DanmuService.DanmuLine("清淡", "全员装死", 0L);
        assertTrue(danmuService.allow(userId, 0, flat), "第一条必须放行");
        assertFalse(danmuService.allow(userId, 1, new DanmuService.DanmuLine("涨停", "封死了", 3L)),
                "只隔 1 跳：任意两条的最小间隔没到");
        assertFalse(danmuService.allow(userId, 4, flat),
                "同一句文案在记忆里：重复的不推");
        assertTrue(danmuService.allow(userId, 4, new DanmuService.DanmuLine("涨停", "另一句", 3L)),
                "过了最小间隔且不是重复句，应当放行");
        assertFalse(danmuService.allow(userId, 8, new DanmuService.DanmuLine("涨停", "又一句", 3L)),
                "涨停类别只隔 4 跳，仍在同类冷却里");
        assertTrue(danmuService.allow(userId, 15, new DanmuService.DanmuLine("涨停", "再一句", 3L)),
                "同类冷却（10 跳）已过，可以再说一次");
        assertTrue(danmuService.allow(userId, 3, new DanmuService.DanmuLine("大盘", "跨日也来一句", 0L)),
                "跳序回零视为新的一天：冷却表作废，第二天开盘第一跳就该能说话");
    }

    /** 载荷必须是带 stockId 的 JSON：个股页的股吧要分得清"在说谁"。 */
    @Test
    void pushedPayloadCarriesCategoryAndFocusStock() throws Exception {
        String json = DanmuService.payload(new DanmuService.DanmuLine("涨停", "封死了", 7L));
        assertTrue(json.contains("\"category\""), "payload must carry the category: " + json);
        assertTrue(json.contains("\"stockId\":7"), "payload must carry the focus stock id: " + json);
        assertTrue(json.contains("封死了"), "payload must carry the text: " + json);
    }

    // ---- 软依赖投递与不落库 ----

    @Test
    void maybePushNeverPersistsDanmuToUserMessage() {
        long userId = worldSeeder.seedUser("danmu_nodb1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 1414213L);
        marketStateService.startDay(userId);

        advance(userId, 90);
        danmuService.maybePush(userId, safeBoard(userId));

        List<UserMessage> messages = userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
        for (UserMessage message : messages) {
            assertTrue(!"DANMU".equals(message.getType()),
                    "danmu must never be persisted as a user_message (found a DANMU row)");
        }
    }

    @Test
    void danmuServiceIsInjectableAndSoftDependencyIsCompatible() {
        // 无论 PushService bean 是否存在，DanmuService 均可注入且 maybePush 静默兼容
        assertNotNull(danmuService, "DanmuService bean must be injectable");
        QuoteBoard board = flatBoard(1000.0);
        danmuService.maybePush(1L, board); // 不抛异常即通过
        danmuService.maybePush(2L, flatBoard(970.0)); // 二级 userId，覆盖多用户走 PushService 分支
    }

    // ---- 辅助 ----

    /** 构造 24 股全平盘 QuoteBoard（current=prevClose，sealed=NONE，未停牌），指数=昨收。 */
    private static QuoteBoard flatBoard(double indexValue) {
        QuoteBoard board = new QuoteBoard();
        for (StockDef def : StockDefs.ALL) {
            Quote q = new Quote();
            q.setStockId(def.id());
            q.setPrevClose(def.basePrice());
            q.setCurrent(def.basePrice());
            board.getQuotes().put(def.id(), q);
        }
        board.setIndexValue(indexValue);
        board.setPrevIndex(indexValue);
        return board;
    }

    /** 取该 userId 当前板，缺失时用平盘替身（主线仅验证不落库与不抛异常）。 */
    private QuoteBoard safeBoard(long userId) {
        return flatBoard(1000.0);
    }

    /** 推进若干跳，自动处理午休与跨日边界。 */
    private void advance(long userId, int ticks) {
        for (int i = 0; i < ticks; i++) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
        }
    }

    /** 午休恢复下午交易；收盘后开启下一交易日。 */
    private void handleBoundary(long userId) {
        UserGameState gs = gameState(userId);
        switch (gs.getMarketState()) {
            case "LUNCH_BREAK" -> marketStateService.resumeAfternoon(userId);
            case "CLOSED" -> marketStateService.startDay(userId);
            default -> { }
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
}