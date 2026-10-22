package com.holdhundredyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 午间/盘后消息流（spec §7.4）：午休抽 0~2 条并把冲击记成下午开盘跳空；
 * 盘后抽 0~2 条并把冲击写进次日 gap_pct。随机流由测试注入，避免"这天恰好 0 条"。
 */
@SpringBootTest
@Transactional
class NewsFlowTest {

    @Autowired private EventFactory eventFactory;
    @Autowired private WorldSeeder worldSeeder;
    @Autowired private MarketStateService marketStateService;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserStockStateMapper userStockStateMapper;
    @Autowired private UserMessageMapper userMessageMapper;

    @Test
    void lunchNewsBecomesAfternoonGapAndIsConsumedOnResume() {
        long userId = worldSeeder.seedUser("news_lunch1", "$2a$10$bcrypthash");
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        UserGameState gs = gameState(userId);

        int delivered = eventFactory.publishLunchNews(userId, gs, board, rngWithNews());
        assertTrue(delivered > 0, "controlled rng must deliver at least one lunch message");
        assertFalse(board.getAfternoonGaps().isEmpty(), "lunch news must leave an afternoon gap");

        marketStateService.toLunchBreak(userId);
        assertEquals("LUNCH_BREAK", gameState(userId).getMarketState());
        assertFalse(board.getAfternoonGaps().isEmpty(), "lunch messages must be queued before the afternoon open");

        marketStateService.resumeAfternoon(userId);
        assertEquals("AFTERNOON_OPEN", gameState(userId).getMarketState());
        assertTrue(board.getAfternoonGaps().isEmpty(), "afternoon gaps must be consumed exactly once");

        List<UserMessage> eventMessages = userMessageMapper.selectList(
                new LambdaQueryWrapper<UserMessage>().eq(UserMessage::getUserId, userId));
        assertTrue(eventMessages.stream().anyMatch(m -> "EVENT".equals(m.getType())
                        && m.getGameDay() == gs.getCurrentDay()),
                "lunch news must be persisted as in-game messages");
    }

    @Test
    void afterHoursNewsWritesNextDayGap() {
        long userId = worldSeeder.seedUser("news_after1", "$2a$10$bcrypthash");
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        UserGameState gs = gameState(userId);

        int delivered = eventFactory.publishAfterHoursNews(userId, gs, board, rngWithNews());
        assertTrue(delivered > 0, "controlled rng must deliver at least one after-hours message");

        List<UserStockState> states = userStockStateMapper.selectList(
                new LambdaQueryWrapper<UserStockState>().eq(UserStockState::getUserId, userId));
        assertTrue(states.stream().anyMatch(s -> s.getGapPct() != null && Math.abs(s.getGapPct()) > 1e-9),
                "after-hours news must write a non-zero gap_pct for the next open");
        assertTrue(states.stream().allMatch(s -> s.getGapPct() == null || Math.abs(s.getGapPct()) <= 0.2),
                "gap_pct must stay within the daily limit");
    }

    /** 找到第一条 nextInt(3) &gt; 0 的种子，再用同一 seed 重放相同随机流。 */
    private static Random rngWithNews() {
        for (long seed = 0; seed < 100; seed++) {
            if (new Random(seed).nextInt(3) > 0) {
                return new Random(seed);
            }
        }
        throw new IllegalStateException("no seed produced a non-zero news count");
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
    }
}
