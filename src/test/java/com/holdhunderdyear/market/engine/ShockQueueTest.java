package com.holdhunderdyear.market.engine;

import com.holdhunderdyear.market.engine.PriceShock.Decay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShockQueueTest {

    @Test
    void linearShockSpreadsTotalEvenlyAcrossTicksThenRemovesItself() {
        ShockQueue queue = new ShockQueue();
        queue.enqueue(new PriceShock(1L, false, 0.06, 6, Decay.LINEAR));

        for (int tick = 0; tick < 6; tick++) {
            assertEquals(0.01, queue.consume(1L), 1e-9,
                    "tick " + tick + " of LINEAR must return totalPct/totalTicks");
        }
        assertEquals(0.0, queue.consume(1L), 1e-9, "exhausted shock must contribute nothing");
        assertEquals(0, queue.activeCount(), "exhausted shock must be removed from the queue");
    }

    @Test
    void onceShockDeliversFullMagnitudeOnFirstTickOnly() {
        ShockQueue queue = new ShockQueue();
        queue.enqueue(new PriceShock(1L, false, 0.05, 3, Decay.ONCE));

        assertEquals(0.05, queue.consume(1L), 1e-9, "first tick must return the full totalPct");
        assertEquals(0.0, queue.consume(1L), 1e-9, "ticks after the first must return 0");
        assertEquals(0.0, queue.consume(1L), 1e-9, "ticks after the first must return 0");
        assertEquals(0, queue.activeCount(), "shock must be removed once its ticks are used up");
    }

    @Test
    void expShockIsFrontLoadedAndSumsBackToTotal() {
        ShockQueue queue = new ShockQueue();
        queue.enqueue(new PriceShock(1L, false, 0.10, 4, Decay.EXP));

        double first = queue.consume(1L);
        double second = queue.consume(1L);
        double third = queue.consume(1L);
        double last = queue.consume(1L);

        assertEquals(0.04, first, 1e-12, "first tick must be totalPct * n / (n*(n+1)/2)");
        assertEquals(0.01, last, 1e-12, "last tick must be totalPct * 1 / (n*(n+1)/2)");
        assertTrue(first > last, "EXP decay must be front-loaded");
        assertEquals(0.10, first + second + third + last, 1e-9,
                "all ticks must sum back to totalPct");
        assertEquals(0, queue.activeCount());
    }

    @Test
    void twoShocksOnTheSameStockStackAdditively() {
        ShockQueue queue = new ShockQueue();
        queue.enqueue(new PriceShock(1L, false, 0.06, 6, Decay.LINEAR));
        queue.enqueue(new PriceShock(1L, false, 0.03, 3, Decay.LINEAR));

        assertEquals(0.06 / 6 + 0.03 / 3, queue.consume(1L), 1e-9,
                "same tick consumption must be the sum of both shocks");

        // 再消费 2 跳让 3 跳 shock 耗尽：之后同跳只剩 6 跳 shock 的份额
        queue.consume(1L);
        queue.consume(1L);
        assertEquals(0.01, queue.consume(1L), 1e-9);
        assertEquals(1, queue.activeCount());
    }

    @Test
    void marketWideShockHitsEveryStockWhileStockShockStaysLocal() {
        ShockQueue queue = new ShockQueue();
        queue.enqueue(new PriceShock(1L, false, 0.06, 6, Decay.LINEAR)); // 个股：仅股票 1
        queue.enqueue(new PriceShock(0L, true, 0.02, 2, Decay.LINEAR)); // 全市场

        // 股票 1 同跳看到自己的 shock + 全市场 shock
        assertEquals(0.06 / 6 + 0.02 / 2, queue.consume(1L), 1e-9);
        // 股票 2 只看到全市场 shock；股票 1 的个股 shock 不受影响
        assertEquals(0.02 / 2, queue.consume(2L), 1e-9);
        // 全市场 shock 2 跳耗尽被移除；个股 shock 原封不动（仍剩 5 跳）
        assertEquals(1, queue.activeCount());
        assertEquals(0.06 / 6, queue.consume(1L), 1e-9);
        assertEquals(0.0, queue.consume(2L), 1e-9, "no market shock left for other stocks");
    }

    @Test
    void constructorRejectsNonPositiveTicks() {
        assertThrows(IllegalArgumentException.class,
                () -> new PriceShock(1L, false, 0.05, 0, Decay.LINEAR));
        assertThrows(IllegalArgumentException.class,
                () -> new PriceShock(1L, false, 0.05, -1, Decay.EXP));
    }

    @Test
    void clearDropsAllShocks() {
        ShockQueue queue = new ShockQueue();
        queue.enqueue(new PriceShock(1L, false, 0.06, 6, Decay.LINEAR));
        queue.enqueue(new PriceShock(0L, true, 0.02, 2, Decay.LINEAR));
        assertEquals(2, queue.activeCount());

        queue.clear();
        assertEquals(0, queue.activeCount());
        assertEquals(0.0, queue.consume(1L), 1e-9);
    }
}
