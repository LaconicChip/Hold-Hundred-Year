package com.holdhunderdyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.game.constants.EventSkeletons;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.constants.StockDefs.StockDef;
import com.holdhunderdyear.game.model.enums.EraCode;
import com.holdhunderdyear.market.MarketStateService;
import com.holdhunderdyear.market.QuoteBoardRegistry;
import com.holdhunderdyear.market.WorldTickService;
import com.holdhunderdyear.market.entity.GameEvent;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.GameEventMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件工厂集成测试（hold_year_test 库，方法级事务自动回滚）：静态纯函数的统计行为
 * （真伪概率带、纪元过滤、持仓偏置、槽位填充），以及掷骰落库（消息 + 事件 + 冲击）、
 * PENDING 消息三态生命周期（PENDING → CONFIRMED/DENIED + followup）、
 * clearPending 后无悬空 followup 引用。全部固定 world_seed（P6 确定性）。
 */
@SpringBootTest
@Transactional
class EventFactoryTest {

    @Autowired
    private EventFactory eventFactory;

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
    private UserMessageMapper userMessageMapper;

    @Autowired
    private GameEventMapper gameEventMapper;

    // ---- 静态纯函数 ----

    @Test
    void rollTruthMatchesConfiguredBands() {
        assertEquals(0.45, truthFrequency(byCode("rumor-restructure"), 5000), 0.05,
                "RUMOR truth frequency must stay near 0.45");
        assertEquals(0.80, truthFrequency(byCode("fund-price-up"), 5000), 0.05,
                "MEDIA truth frequency must stay near 0.80");
        assertEquals(0.70, truthFrequency(byCode("rumor-insider-order"), 5000), 0.05,
                "INSIDER truth frequency must stay near 0.70");
        Random rng = new Random(3);
        for (int i = 0; i < 100; i++) {
            assertTrue(EventFactory.rollTruth(byCode("fund-earnings-preinc-good"), rng),
                    "AUTHORITY must always confirm");
        }
    }

    @Test
    void pickSkeletonExcludesSkeletonsOutsideAllowedEras() {
        Set<EraCode> allowed = EnumSet.range(EraCode.BULL_START, EraCode.POST_CRASH);
        Random rng = new Random(7);
        for (int i = 0; i < 5000; i++) {
            EventSkeleton picked = EventFactory.pickSkeleton(EventSkeletons.ALL, allowed, rng);
            assertNotEquals("policy-trade-tariff", picked.code(),
                    "TRADE_WAR-only skeleton must never be picked");
            assertNotEquals("policy-crackdown-chip", picked.code(),
                    "TRADE_WAR-only skeleton must never be picked");
        }
    }

    @Test
    void pickTargetStockBiasesTowardHoldings() {
        EventSkeleton skeleton = byCode("rumor-restructure");
        EventSkeleton.Variant variant = skeleton.variants().get(0);
        List<StockDef> candidates = StockDefs.ALL.subList(0, 4);
        Set<Long> holdings = Set.of(1L);
        Random rng = new Random(11);
        int hits = 0;
        for (int i = 0; i < 5000; i++) {
            if (EventFactory.pickTargetStock(skeleton, variant, candidates, holdings, rng) == 1L) {
                hits++;
            }
        }
        assertEquals(0.40, hits / 5000.0, 0.05,
                "HOLDING_BIASED weight 2/(2+1+1+1) must land near 0.4");
    }

    @Test
    void fillSlotsResolvesEveryWhitelistedSlot() {
        Random rng = new Random(20260905L);
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            for (EventSkeleton.Variant variant : skeleton.variants()) {
                assertFalse(EventFactory.fillSlots(variant.text(), "鸱夷酒业", rng).contains("{"),
                        "unresolved slot in " + skeleton.code());
                for (String followup : skeleton.followupTexts()) {
                    assertFalse(EventFactory.fillSlots(followup, "鸱夷酒业", rng).contains("{"),
                            "unresolved slot in followup of " + skeleton.code());
                }
            }
        }
    }

    @Test
    void rollAmountFormatsYiAndWan() {
        Random alwaysOne = new Random() {
            @Override
            public double nextDouble() {
                return 1.0;
            }
        };
        assertEquals("80.0 亿", EventFactory.rollAmount(alwaysOne), "v >= 1 must format as 亿");
        Random alwaysZero = new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
        assertEquals("3000 万", EventFactory.rollAmount(alwaysZero), "v < 1 must format as 万");
    }

    // ---- 集成：事件掷骰与生命周期 ----

    @Test
    void eventRollWritesMessageGameEventAndShock() {
        long userId = worldSeeder.seedUser("event_roll1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 2718281828L);
        marketStateService.startDay(userId);

        UserMessage message = null;
        GameEvent event = null;
        int ticks = 0;
        while (ticks < 120 && event == null) {
            int before = eventMessages(userId).size();
            worldTickService.tickOne(userId);
            handleBoundary(userId);
            ticks++;
            List<UserMessage> messages = eventMessages(userId);
            if (messages.size() > before) {
                message = messages.get(messages.size() - 1);
                event = latestGameEvent(userId);
            }
        }
        assertNotNull(message, "an EVENT message must roll within 120 ticks");
        assertNotNull(event, "a game_event row must accompany the EVENT message");
        assertEquals(message.getTitle(), event.getTitle(), "game_event must mirror the message title");
        assertEquals(message.getGameDay(), event.getGameDay(), "game_event must share the message gameDay");
        if (!"MARKET".equals(event.getScope())) {
            QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
            assertTrue(board.getShocks().activeCount() > 0,
                    "a just-enqueued event shock must still be active");
        }
    }

    @Test
    void pendingEventMessageResolvesWithFollowup() {
        long userId = worldSeeder.seedUser("event_life1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 1618033L);
        marketStateService.startDay(userId);

        UserMessage pending = null;
        int ticks = 0;
        while (pending == null && ticks < 400) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
            ticks++;
            pending = eventMessages(userId).stream()
                    .filter(m -> "PENDING".equals(m.getTruthStatus()))
                    .findFirst().orElse(null);
        }
        assertNotNull(pending, "a PENDING event message must appear within 400 ticks");

        UserMessage resolved = pending;
        int more = 0;
        while (more < 400) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
            more++;
            resolved = userMessageMapper.selectById(pending.getId());
            if (resolved != null
                    && ("CONFIRMED".equals(resolved.getTruthStatus())
                        || "DENIED".equals(resolved.getTruthStatus()))
                    && !followupsOf(userId, pending.getId()).isEmpty()) {
                break;
            }
        }
        assertTrue("CONFIRMED".equals(resolved.getTruthStatus())
                        || "DENIED".equals(resolved.getTruthStatus()),
                "truth status must resolve, got " + resolved.getTruthStatus());
        assertFalse(followupsOf(userId, pending.getId()).isEmpty(),
                "a followup message must reference the original");
    }

    @Test
    void clearPendingLeavesNoDanglingFollowupReferences() {
        long userId = worldSeeder.seedUser("event_ghost1", "$2a$10$bcrypthash");
        setWorldSeed(userId, 31415926L);
        marketStateService.startDay(userId);

        advance(userId, 300);
        eventFactory.clearPending(userId);
        advance(userId, 300);

        Set<Long> ids = userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                        .eq(UserMessage::getUserId, userId))
                .stream().map(UserMessage::getId).collect(Collectors.toSet());
        for (UserMessage message : userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId))) {
            assertTrue(message.getFollowupOf() == null || ids.contains(message.getFollowupOf()),
                    "dangling followup_of on message " + message.getId());
        }
    }

    // ---- 辅助 ----

    private static double truthFrequency(EventSkeleton skeleton, int rolls) {
        Random rng = new Random(42);
        int hits = 0;
        for (int i = 0; i < rolls; i++) {
            if (EventFactory.rollTruth(skeleton, rng)) {
                hits++;
            }
        }
        return (double) hits / rolls;
    }

    private static EventSkeleton byCode(String code) {
        return EventSkeletons.ALL.stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
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

    private List<UserMessage> eventMessages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getType, "EVENT")
                .orderByAsc(UserMessage::getId));
    }

    private List<UserMessage> followupsOf(long userId, long messageId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getFollowupOf, messageId));
    }

    private GameEvent latestGameEvent(long userId) {
        List<GameEvent> events = gameEventMapper.selectList(new LambdaQueryWrapper<GameEvent>()
                .eq(GameEvent::getUserId, userId)
                .orderByDesc(GameEvent::getId));
        return events.isEmpty() ? null : events.get(0);
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
