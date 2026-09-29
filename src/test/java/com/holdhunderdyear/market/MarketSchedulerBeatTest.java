package com.holdhunderdyear.market;

import com.holdhunderdyear.game.model.enums.GameMode;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.ws.WsSessionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 心跳预判（{@link MarketScheduler#dueForBeat}）单元测试：纯 mock，不碰库不启线程。
 * 锁住两件事——冷用户（还没缓存行情板）一秒只放行一次，别把 100ms 心跳变成每秒 10 次
 * 空查库；有板的用户只看自己本局档案的跳间隔，快速模式 500ms 就该放行。
 */
class MarketSchedulerBeatTest {

    @Test
    void coldUserPassesOnceThenGetsThrottled() {
        QuoteBoardRegistry registry = mock(QuoteBoardRegistry.class);
        when(registry.peek(1L)).thenReturn(null);
        when(registry.peek(2L)).thenReturn(null);
        MarketScheduler scheduler = scheduler(registry);

        assertTrue(scheduler.dueForBeat(1L), "first beat of a board-less user must pass (load the board / resume after restart)");
        assertFalse(scheduler.dueForBeat(1L), "the same user must not hit the database on every 100ms beat");
        assertTrue(scheduler.dueForBeat(2L), "the throttle is per user: another idle user still gets its own beat");
    }

    @Test
    void warmBoardIsGatedByItsOwnTickInterval() {
        QuoteBoard board = new QuoteBoard();
        QuoteBoardRegistry registry = mock(QuoteBoardRegistry.class);
        when(registry.peek(3L)).thenReturn(board);
        MarketScheduler scheduler = scheduler(registry);

        assertTrue(scheduler.dueForBeat(3L), "an unstamped board is due immediately");
        board.setLastTickAtNanos(System.nanoTime());
        assertFalse(scheduler.dueForBeat(3L), "a board that just ticked must wait out the tick interval");

        board.setGameMode(GameMode.QUICK);
        board.setLastTickAtNanos(System.nanoTime() - 600_000_000L);
        assertTrue(scheduler.dueForBeat(3L), "quick mode ticks every 500ms, so 600ms idle is due");
    }

    private static MarketScheduler scheduler(QuoteBoardRegistry registry) {
        return new MarketScheduler(mock(WsSessionRegistry.class), registry,
                mock(UserGameStateMapper.class), mock(WorldTickService.class),
                mock(MarketStateService.class));
    }
}
