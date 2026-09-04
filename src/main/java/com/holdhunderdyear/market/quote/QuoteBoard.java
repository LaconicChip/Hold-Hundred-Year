package com.holdhunderdyear.market.quote;

import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.engine.ShockQueue;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每用户的内存行情板（P9 内存权威）：24 只股票的 {@link Quote}、等权指数、
 * 冲击队列与指数分时缓冲的完整世界视图。持久化仅做 Checkpoint 快照，
 * 运行时读写的权威数据都在本类内。
 *
 * <p>并发协议：本类由 per-user 世界锁（{@link #lock}）保护——调度、快进、交易三方互斥
 * （P5）；Quote 与 ShockQueue 均无内部同步，锁外不得读写。
 *
 * <p>非线程安全（锁协议同上）：除 {@link #lock()} 外不做任何同步。
 */
@Data
public class QuoteBoard {

    /** 指数分时缓冲容量：与个股分时同为 90 点（spec §11 指数分时）。 */
    private static final int INDEX_INTRADAY_CAP = 90;

    /** LinkedHashMap 保持 StockDefs 档案顺序。 */
    private Map<Long, Quote> quotes = new LinkedHashMap<>();

    private double indexValue;
    private double prevIndex;

    /** per-user 世界锁：调度/快进/交易互斥（P5）。 */
    private final ReentrantLock lock = new ReentrantLock();

    private final ShockQueue shocks = new ShockQueue();

    private int tickIndex;

    /** 指数分时缓冲（cap 90）；toString 排除，避免日志倾倒 90 个值。 */
    @ToString.Exclude
    private Deque<Double> indexIntraday = new ArrayDeque<>();

    /**
     * 从 stock_state 快照恢复行情板：价格、连板数、停牌、ST 从实体读取；
     * gapPct 不在此应用——那是开盘管线（跳空落地）的事；sealed=NONE、garchK=1、
     * dayAvg/tickCount=0、分时缓冲空（Checkpoint 不存分时，恢复后重新积累）。
     * prevIndex = indexValue（spec §10：index_value 即昨收/当前快照同列）。
     */
    public static QuoteBoard restore(List<UserStockState> states, double indexValue, int tickIndex) {
        QuoteBoard board = new QuoteBoard();
        for (UserStockState state : states) {
            Quote quote = new Quote();
            quote.setStockId(state.getStockId());
            quote.setPrevClose(state.getPrevClose().doubleValue());
            double current = state.getCurrentPrice().doubleValue();
            quote.setCurrent(current);
            quote.setOpen(priceOr(state.getOpenPrice(), current));
            quote.setHigh(priceOr(state.getHighPrice(), current));
            quote.setLow(priceOr(state.getLowPrice(), current));
            quote.setLimitUpStreak(state.getLimitUpStreak());
            quote.setHalted(Boolean.TRUE.equals(state.getHalted()));
            quote.setSt(Boolean.TRUE.equals(state.getIsSt()));
            board.quotes.put(state.getStockId(), quote);
        }
        board.indexValue = indexValue;
        board.prevIndex = indexValue;
        board.tickIndex = tickIndex;
        return board;
    }

    /** 取行情；未知 stockId 直接抛（编程错误应尽早暴露）。 */
    public Quote quote(long stockId) {
        Quote quote = quotes.get(stockId);
        if (quote == null) {
            throw new IllegalArgumentException("Unknown stockId: " + stockId);
        }
        return quote;
    }

    /** 追加指数分时点并推进 indexValue；缓冲超 90 淘汰最旧。 */
    public void appendIndexTick(double value) {
        indexValue = value;
        indexIntraday.addLast(value);
        if (indexIntraday.size() > INDEX_INTRADAY_CAP) {
            indexIntraday.removeFirst();
        }
    }

    /** schema 中 open/high/low 可空：缺失时退化为当前价（无日内历史可恢复）。 */
    private static double priceOr(BigDecimal value, double fallback) {
        return value != null ? value.doubleValue() : fallback;
    }
}
