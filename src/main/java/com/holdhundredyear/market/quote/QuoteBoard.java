package com.holdhundredyear.market.quote;

import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.VolRegime;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.engine.ShockQueue;
import com.holdhundredyear.world.EraScheduler;
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

    /** 本局游戏档案（标准/快速）：涨跌停幅度、跳毫秒、收官日都由它决定。 */
    private GameMode gameMode = GameMode.STANDARD;

    /** 上一跳完成的挂钟纳秒（0=未打过点）：调度器按 gameMode.tickMs 判定该不该推进，
     *  快进与测试直接调 tickOne 不经过这里，因此不影响"快进=逐跳"的等价性。 */
    @ToString.Exclude
    private volatile long lastTickAtNanos;

    /** 把本局涨跌停幅度下发给每只票（恢复行情板与开盘时各一次）。 */
    public void applyLimits() {
        for (Map.Entry<Long, Quote> entry : quotes.entrySet()) {
            Quote quote = entry.getValue();
            quote.setLimitPct(gameMode.getLimitPct());
            // ETF 不设涨跌停：价格只由跟踪标的决定（见 Quote.limitless）
            quote.setLimitless(StockDefs.isEtf(entry.getKey()));
        }
    }

    /** 距上一跳已过多少毫秒（未打过点返回极大值，表示可以立刻推进）。 */
    public long tickElapsedMillis() {
        return lastTickAtNanos == 0L ? Long.MAX_VALUE : (System.nanoTime() - lastTickAtNanos) / 1_000_000L;
    }

    /** per-user 世界锁：调度/快进/交易互斥（P5）。 */
    private final ReentrantLock lock = new ReentrantLock();

    private final ShockQueue shocks = new ShockQueue();

    private int tickIndex;

    /** 下一跳事件掷骰闸门（绝对跳 = gameDay*TOTAL_TICKS + tickIndex）：纯内存态、不持久化，
     *  新板默认 10（重启即回到默认值，重启丢 pending 同理，spec §8 已授权）。
     *  由事件工厂在每次掷骰时按 rng.nextInt(10,21) 后移，lombok @Data 自动访问器。 */
    private long nextRollAbsTick = 10;

    /** 当日记元（开盘管线写入；restore 不设置，默认 null）。 */
    private EraScheduler.EraInstance currentEra;

    /** 当日波动体制（开盘管线写入；restore 不设置，默认 NORMAL）。 */
    private VolRegime volRegime = VolRegime.NORMAL;

    /** 反向意外日（spec §4.3，8% 概率掷出；开盘管线写入，restore 不设置）。 */
    private boolean reverseDay;

    /** 熔断日标记（spec §4.4：上午指数 ≤ −7% 提前收盘；开盘只立标记，判定在盘中引擎）。 */
    private boolean circuitBreakerDay;

    /** 指数分时缓冲（cap 90）；toString 排除，避免日志倾倒 90 个值。 */
    @ToString.Exclude
    private Deque<Double> indexIntraday = new ArrayDeque<>();

    /** 午间消息造成的下午开盘跳空（stockId → 幅度，同票多消息累加）。纯内存：
     *  午休期间刷新/重启会丢（消息本身已落库），属可接受取舍；正常调度与快进都会在
     *  resumeAfternoon 消费它。 */
    @ToString.Exclude
    private Map<Long, Double> afternoonGaps = new LinkedHashMap<>();

    /** 午休开始打点（System.nanoTime()，0=未打点）：调度器在首个 LUNCH_BREAK 轮懒打点，
     *  用于本局 GameMode#getLunchMs() 的午休恢复判定。重启后从零重计（可接受）；
     *  纯挂钟计时，绝不进入随机数协议、不影响确定性。volatile 兜底跨线程读，
     *  写读均在世界锁内。 */
    @ToString.Exclude
    private volatile long lunchStartedNanos;

    /** 暂停实时推进（spec §12.1 消息分级）：玩家被重要消息的模态弹窗挡住时，他的这一份世界
     *  冻在原地，关掉弹窗才继续。纯内存挂钟闸门，只有调度器读它——快进、结算与随机协议一律
     *  不看它，所以"同 seed 同结果"与"快进=逐跳等价"都不受影响（P5/P6 不破）。
     *  不持久化：进程重启后回到未暂停，避免"世界被冻在无人看管的暂停态"。 */
    @ToString.Exclude
    private volatile boolean paused;

    /**
     * 从 stock_state 快照恢复行情板：价格、连板数、停牌、ST 从实体读取；
     * gapPct 不在此应用——那是开盘管线（跳空落地）的事；sealed=NONE、garchK=1、
     * dayAvg/tickCount=0、分时缓冲空（Checkpoint 不存分时，恢复后重新积累）。
     * prevIndex 取 user_game_state.prev_index（当日开盘基准指数）：与 index_value 分列后，
     * 盘中重启恢复出的熔断阈值基数仍是昨收，不再被逐跳 checkpoint 写进的盘中值污染。
     * 当日上下文（currentEra/volRegime/reverseDay/circuitBreakerDay）不在此恢复——
     * 由开盘管线（MarketStateService.openDay）写入。
     */
    public static QuoteBoard restore(List<UserStockState> states, double indexValue,
                                     double prevIndex, int tickIndex) {
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
            quote.setLastClosePct(state.getDayChangePct());
            board.quotes.put(state.getStockId(), quote);
        }
        board.indexValue = indexValue;
        board.prevIndex = prevIndex;
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

    /** 午休已流逝毫秒：尚未打点时先打点并返回 0（首个调度轮恒为 0，下一轮起正常计时）；
     *  本方法是唯一打点路径，起点一旦确立不因重复调用推迟。 */
    public long lunchElapsedMillis() {
        if (lunchStartedNanos == 0) {
            lunchStartedNanos = System.nanoTime();
            return 0;
        }
        return (System.nanoTime() - lunchStartedNanos) / 1_000_000;
    }

    /** 清零午休打点（下午开盘后由恢复方调用），保证下一次午休重新计时。 */
    public void clearLunchTiming() {
        lunchStartedNanos = 0L;
    }

    /** 记一笔午间冲击，下午开盘时合并落地；同票多笔累加。 */
    public void addAfternoonGap(long stockId, double gapPct) {
        afternoonGaps.merge(stockId, gapPct, Double::sum);
    }

    /** 取走并清空午间跳空表：只有 resumeAfternoon 调，保证同一笔只落地一次。 */
    public Map<Long, Double> takeAfternoonGaps() {
        Map<Long, Double> taken = new LinkedHashMap<>(afternoonGaps);
        afternoonGaps.clear();
        return taken;
    }

    /** schema 中 open/high/low 可空：缺失时退化为当前价（无日内历史可恢复）。 */
    private static double priceOr(BigDecimal value, double fallback) {
        return value != null ? value.doubleValue() : fallback;
    }
}
