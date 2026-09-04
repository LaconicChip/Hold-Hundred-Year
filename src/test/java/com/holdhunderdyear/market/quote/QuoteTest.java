package com.holdhunderdyear.market.quote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteTest {

    private Quote quote(double prevClose) {
        Quote q = new Quote();
        q.setPrevClose(prevClose);
        return q;
    }

    @Test
    void limitPricesForNormalStocks() {
        Quote q = quote(168.00);
        assertEquals(184.80, q.limitUpPrice(), 1e-9);
        assertEquals(151.20, q.limitDownPrice(), 1e-9);

        q = quote(6.00);
        assertEquals(6.60, q.limitUpPrice(), 1e-9);
        assertEquals(5.40, q.limitDownPrice(), 1e-9);

        q = quote(4.50);
        assertEquals(4.95, q.limitUpPrice(), 1e-9);
        assertEquals(4.05, q.limitDownPrice(), 1e-9);
    }

    @Test
    void limitPricesForStStocks() {
        Quote q = quote(45.00);
        q.setSt(true);
        assertEquals(47.25, q.limitUpPrice(), 1e-9);
        assertEquals(42.75, q.limitDownPrice(), 1e-9);

        q = quote(6.00);
        q.setSt(true);
        assertEquals(6.30, q.limitUpPrice(), 1e-9);
        assertEquals(5.70, q.limitDownPrice(), 1e-9);
    }

    @Test
    void halfUpRoundingOnLimitPrices() {
        assertEquals(8.25, quote(7.50).limitUpPrice(), 1e-9,
                "7.50*1.10 = 8.25 exactly in decimal");
        assertEquals(10.99, quote(9.99).limitUpPrice(), 1e-9,
                "9.99*1.10 = 10.989 must HALF_UP to 10.99");
    }

    @Test
    void dayChangePct() {
        Quote q = quote(100.0);
        q.setCurrent(104.0);
        assertEquals(0.04, q.dayChangePct(), 1e-12);
    }

    @Test
    void appendTickUpdatesCurrentHighLowDayAvgAndIntraday() {
        Quote q = quote(100.0);
        q.setOpen(100.0);
        q.setHigh(100.0);
        q.setLow(100.0);
        q.setCurrent(100.0);

        q.appendTick(102.0);
        assertEquals(102.0, q.getCurrent(), 1e-9);
        assertEquals(102.0, q.getHigh(), 1e-9);
        assertEquals(100.0, q.getLow(), 1e-9);
        assertEquals(102.0, q.getDayAvg(), 1e-9);
        assertEquals(1, q.getTickCount());
        assertEquals(1, q.getIntraday().size());
        assertEquals(102.0, q.getIntraday().getFirst(), 1e-9);

        q.appendTick(98.0);
        assertEquals(98.0, q.getCurrent(), 1e-9);
        assertEquals(102.0, q.getHigh(), 1e-9);
        assertEquals(98.0, q.getLow(), 1e-9);
        assertEquals(100.0, q.getDayAvg(), 1e-9, "dayAvg must be the running mean (102+98)/2");
        assertEquals(2, q.getTickCount());
        assertEquals(2, q.getIntraday().size());
        assertEquals(102.0, q.getIntraday().getFirst(), 1e-9);
        assertEquals(98.0, q.getIntraday().getLast(), 1e-9);
    }

    @Test
    void intradayBufferCapsAt90DroppingOldest() {
        Quote q = quote(100.0);
        q.setHigh(100.0);
        q.setLow(100.0);
        for (int i = 1; i <= 95; i++) {
            q.appendTick(100.0 + i);
        }
        assertEquals(90, q.getIntraday().size());
        assertEquals(106.0, q.getIntraday().getFirst(), 1e-9,
                "the 6th tick's price must survive as the oldest");
        assertEquals(195.0, q.getIntraday().getLast(), 1e-9);
        assertEquals(95, q.getTickCount(), "tickCount is the day-long mean denominator and is not capped");
    }

    @Test
    void canBuyAndCanSellMatchLimitBoundaries() {
        Quote q = quote(100.0); // up 110.00 / down 90.00
        q.setCurrent(109.99);
        assertTrue(q.canBuy());
        q.setCurrent(110.00);
        assertFalse(q.canBuy(), "pinned at the limit-up price must not be buyable");

        q.setCurrent(90.01);
        assertTrue(q.canSell());
        q.setCurrent(90.00);
        assertFalse(q.canSell(), "pinned at the limit-down price must not be sellable");
    }
}
