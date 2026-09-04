package com.holdhunderdyear.market.quote;

import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.enums.SealState;
import com.holdhunderdyear.market.engine.PriceShock;
import com.holdhunderdyear.market.engine.PriceShock.Decay;
import com.holdhunderdyear.market.entity.UserStockState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteBoardTest {

    private UserStockState state(StockDefs.StockDef def) {
        BigDecimal price = BigDecimal.valueOf(def.basePrice());
        UserStockState state = new UserStockState();
        state.setStockId(def.id());
        state.setPrevClose(price);
        state.setOpenPrice(price);
        state.setHighPrice(price);
        state.setLowPrice(price);
        state.setCurrentPrice(price);
        state.setLimitUpStreak(0);
        state.setHalted(false);
        state.setIsSt(false);
        state.setGapPct(0.0);
        return state;
    }

    @Test
    void restoreBuildsQuotesFromStockStates() {
        List<UserStockState> states = new ArrayList<>();
        for (StockDefs.StockDef def : StockDefs.ALL) {
            states.add(state(def));
        }
        // 一只 ST、一只停牌，覆盖实体标志向行情的传导
        states.get(4).setIsSt(true);
        states.get(18).setHalted(true);

        QuoteBoard board = QuoteBoard.restore(states, 1000.0, 5);

        assertEquals(24, board.getQuotes().size());
        assertEquals(1000.0, board.getIndexValue(), 1e-9);
        assertEquals(1000.0, board.getPrevIndex(), 1e-9,
                "index_value snapshot doubles as prevIndex");
        assertEquals(5, board.getTickIndex());

        Quote first = board.quote(1L);
        assertEquals(168.00, first.getPrevClose(), 1e-9);
        assertEquals(168.00, first.getOpen(), 1e-9);
        assertEquals(168.00, first.getHigh(), 1e-9);
        assertEquals(168.00, first.getLow(), 1e-9);
        assertEquals(168.00, first.getCurrent(), 1e-9);
        assertEquals(0, first.getLimitUpStreak());
        assertFalse(first.isHalted());
        assertFalse(first.isSt());
        assertEquals(SealState.NONE, first.getSealed());
        assertEquals(0, first.getSealTicksLeft());
        assertEquals(1.0, first.getGarchK(), 1e-12);
        assertEquals(0.0, first.getDayAvg(), 1e-9);
        assertEquals(0, first.getTickCount());
        assertTrue(first.getIntraday().isEmpty());

        assertTrue(board.quote(5L).isSt(), "isSt must flow from entity to quote");
        assertTrue(board.quote(19L).isHalted(), "halted must flow from entity to quote");

        // LinkedHashMap 保持 StockDefs 档案顺序（列表/分时展示按此遍历）
        List<Long> ids = new ArrayList<>(board.getQuotes().keySet());
        for (int i = 0; i < StockDefs.ALL.size(); i++) {
            assertEquals(StockDefs.ALL.get(i).id(), ids.get(i));
        }
    }

    @Test
    void quoteThrowsForUnknownStockId() {
        QuoteBoard board = QuoteBoard.restore(List.of(state(StockDefs.ALL.get(0))), 1000.0, 0);
        assertThrows(IllegalArgumentException.class, () -> board.quote(999L));
    }

    @Test
    void lockIsUsable() {
        QuoteBoard board = QuoteBoard.restore(List.of(state(StockDefs.ALL.get(0))), 1000.0, 0);
        board.getLock().lock();
        try {
            assertEquals(0, board.getTickIndex());
        } finally {
            board.getLock().unlock();
        }
    }

    @Test
    void shocksQueueAcceptsAndConsumesStockShock() {
        QuoteBoard board = QuoteBoard.restore(List.of(state(StockDefs.ALL.get(0))), 1000.0, 0);
        board.getShocks().enqueue(new PriceShock(1L, false, 0.05, 1, Decay.ONCE));
        assertEquals(0.05, board.getShocks().consumeStock(1L), 1e-9);
        assertEquals(0.0, board.getShocks().consumeStock(1L), 1e-9);
    }

    @Test
    void appendIndexTickTracksIndexAndCapsBufferAt90() {
        QuoteBoard board = QuoteBoard.restore(List.of(state(StockDefs.ALL.get(0))), 1000.0, 0);

        board.appendIndexTick(1001.0);
        board.appendIndexTick(1002.0);
        board.appendIndexTick(1003.0);
        assertEquals(3, board.getIndexIntraday().size());
        assertEquals(1003.0, board.getIndexValue(), 1e-9, "indexValue must be the latest tick");
        assertEquals(1003.0, board.getIndexIntraday().getLast(), 1e-9);

        for (int i = 4; i <= 95; i++) {
            board.appendIndexTick(1000.0 + i);
        }
        assertEquals(90, board.getIndexIntraday().size());
        assertEquals(1006.0, board.getIndexIntraday().getFirst(), 1e-9,
                "the 6th tick's value must survive as the oldest");
        assertEquals(1095.0, board.getIndexIntraday().getLast(), 1e-9);
        assertEquals(1095.0, board.getIndexValue(), 1e-9);
    }
}
