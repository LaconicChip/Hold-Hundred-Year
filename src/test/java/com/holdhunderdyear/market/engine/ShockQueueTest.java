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
            assertEquals(0.01, queue.consumeStock(1L), 1e-9,
                    "tick " + tick + " of LINEAR must return totalPct/totalTicks");
        }
        assertEquals(0.0, queue.consumeStock(1L), 1e-9, "exhausted shock must contribute nothing");
        assertEquals(0, queue.activeCount(), "exhausted shock must be removed from the queue");
    }

    @Test
    void onceShockDeliversFullMagnitudeOnFirstTickOnly() {
        ShockQueue queue = new ShockQueue();
        queue.enqueue(new PriceShock(1L, false, 0.05, 3, Decay.ONCE));

        assertEquals(0.05, queue.consumeStock(1L), 1e-9, "first tick must return the full totalPct");
        assertEquals(0.0, queue.consumeStock(1L), 1e-9, "ticks after the first must return 0");
        assertEquals(0.0, queue.consumeStock(1L), 1e-9, "ticks after the first must return 0");
        assertEquals(0, queue.activeCount(), "shock must be removed once its ticks are used up");
    }

    @Test
    void expShockIsFrontLoadedAndSumsBackToTotal() {
        ShockQueue queue = new ShockQueue();
        queue.enqueue(new PriceShock(1L, false, 0.10, 4, Decay.EXP));

        double first = queue.consumeStock(1L);
        double second = queue.consumeStock(1L);
        double third = queue.consumeStock(1L);
        double last = queue.consumeStock(1L);

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

        assertEquals(0.06 / 6 + 0.03 / 3, queue.consumeStock(1L), 1e-9,
                "same tick consumption must be the sum of both shocks");

        // 再消费 2 跳让 3 跳 shock 耗尽：之后同跳只剩 6 跳 shock 的份额
        queue.consumeStock(1L);
        queue.consumeStock(1L);
        assertEquals(0.01, queue.consumeStock(1L), 1e-9);
        assertEquals(1, queue.activeCount());
    }

    @Test
    void marketShockDecaysOncePerEngineTickAndAppliesEquallyToAllStocks() {
        ShockQueue queue = new ShockQueue();
        PriceShock market = new PriceShock(0L, true, 0.06, 6, Decay.LINEAR); // 全市场
        PriceShock stock1Only = new PriceShock(1L, false, 0.06, 6, Decay.LINEAR); // 仅股票 1
        queue.enqueue(market);
        queue.enqueue(stock1Only);

        // 引擎一跳的标准调用序列：consumeMarket 一次 + 每股 consumeStock 一次
        double marketTick = queue.consumeMarket();
        assertEquals(0.01, marketTick, 1e-9, "market shock must advance exactly once per engine tick");
        assertEquals(0.01 + 0.06 / 6, marketTick + queue.consumeStock(1L), 1e-9,
                "stock 1 sees the market shock plus its own");
        assertEquals(0.01, marketTick + queue.consumeStock(2L), 1e-9,
                "stock 2 sees only the same market value — it is not stock-parameterized");
        assertEquals(5, market.ticksLeft(), "stock consumption must not advance the market shock");

        // 之后 5 跳：每跳 consumeMarket 各得 0.01（穿插的 consumeStock 不推进、也不包含全市场冲击）
        for (int tick = 2; tick <= 6; tick++) {
            assertEquals(0.0, queue.consumeStock(2L), 1e-9,
                    "consumeStock must not include marketWide shocks");
            assertEquals(0.01, queue.consumeMarket(), 1e-9,
                    "engine tick " + tick + " must still see the full remaining market curve");
        }
        assertEquals(0.0, queue.consumeMarket(), 1e-9, "exhausted market shock must contribute nothing");
        assertEquals(1, queue.activeCount(), "exhausted market shock must be removed from the queue");

        // 收尾：个股冲击剩余 5 跳耗尽后，队列清空
        for (int tick = 0; tick < 5; tick++) {
            queue.consumeStock(1L);
        }
        assertEquals(0.0, queue.consumeStock(1L), 1e-9);
        assertEquals(0, queue.activeCount(), "all shocks exhausted must leave an empty queue");
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
        assertEquals(0.0, queue.consumeStock(1L), 1e-9);
    }
}
