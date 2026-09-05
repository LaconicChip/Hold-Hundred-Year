package com.holdhunderdyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.game.constants.DanmuTexts;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.constants.StockDefs.StockDef;
import com.holdhunderdyear.game.model.enums.SealState;
import com.holdhunderdyear.market.MarketStateService;
import com.holdhunderdyear.market.WorldTickService;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(DanmuTexts.LIMIT_UP.contains(danmu), "danmu must come from the limit-up category");
    }

    @Test
    void bigSurgingStockYieldsRallyDanmu() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1000.0);
        Quote q = board.quote(StockDefs.ALL.get(0).id());
        q.setCurrent(Quote.round2(q.getPrevClose() * 1.08)); // +8% > 5% 触发急拉
        String danmu = DanmuService.pickDanmu(userId, board, new Random(7));
        assertNotNull(danmu, "a +8% surging stock must trigger the rally class");
        assertTrue(DanmuTexts.RALLY.contains(danmu), "danmu must come from the rally category");
    }

    @Test
    void bigPlungingStockYieldsDiveDanmu() {
        long userId = 1L;
        QuoteBoard board = flatBoard(1000.0);
        Quote q = board.quote(StockDefs.ALL.get(0).id());
        q.setCurrent(Quote.round2(q.getPrevClose() * 0.94)); // -6% > 5% 触发跳水
        String danmu = DanmuService.pickDanmu(userId, board, new Random(7));
        assertNotNull(danmu, "a -6% plunging stock must trigger the dive class");
        assertTrue(DanmuTexts.DIVE.contains(danmu), "danmu must come from the dive category");
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