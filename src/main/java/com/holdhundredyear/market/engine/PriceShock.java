package com.holdhundredyear.market.engine;

/**
 * 一次价格冲击（可变对象）：入队后由引擎逐跳消费，衰减曲线决定反应模式（P4）。
 *
 * <p>冲击只负责"幅度随跳数如何分布"，不负责价格如何应用：
 * <ul>
 *   <li>{@link Decay#ONCE}：首跳一次性给足总幅（闪崩式）；</li>
 *   <li>{@link Decay#LINEAR}：每跳均分总幅（匀速漂移式）；</li>
 *   <li>{@link Decay#EXP}：按剩余跳数加权、前重后轻（快速到位后衰减式），
 *       全部跳之和恰为总幅。</li>
 * </ul>
 *
 * <p>totalPct 为比例（0.06 = 6%），符号由调用方决定（正=拉升，负=打压）。
 * 只支持个股 / 全市场两级：SECTOR 级冲击由调用方在入队时按板块成员展开为多只个股 shock。
 *
 * <p>非线程安全：实例存活于 QuoteBoard 内部、由 per-user 世界锁保护（Phase 5），无内部同步。
 */
public class PriceShock {

    /** 衰减曲线：一次性 / 线性均分 / 前重后轻指数式。 */
    public enum Decay { ONCE, LINEAR, EXP }

    private final long stockId;
    private final boolean marketWide;
    private final double totalPct;
    private final int totalTicks;
    private final Decay decay;
    private int ticksLeft;

    public PriceShock(long stockId, boolean marketWide, double totalPct, int totalTicks, Decay decay) {
        if (totalTicks < 1) {
            throw new IllegalArgumentException("totalTicks must be >= 1, got " + totalTicks);
        }
        this.stockId = stockId;
        this.marketWide = marketWide;
        this.totalPct = totalPct;
        this.totalTicks = totalTicks;
        this.decay = decay;
        this.ticksLeft = totalTicks;
    }

    /**
     * 消费一跳并返回本跳幅度；内部自减 ticksLeft，耗尽后继续调用返回 0。
     * EXP 每跳取 totalPct * ticksLeft / (totalTicks*(totalTicks+1)/2)，
     * 剩余跳数 1..n 之和恰为分母，故全跳总和恰为 totalPct。
     */
    public double consume() {
        if (ticksLeft <= 0) {
            return 0.0;
        }
        double value = switch (decay) {
            case ONCE -> ticksLeft == totalTicks ? totalPct : 0.0;
            case LINEAR -> totalPct / totalTicks;
            case EXP -> totalPct * ticksLeft / (totalTicks * (totalTicks + 1) / 2);
        };
        ticksLeft--;
        return value;
    }

    /** 跳数是否已耗尽（耗尽后应由队列移除）。 */
    public boolean exhausted() {
        return ticksLeft <= 0;
    }

    public long stockId() {
        return stockId;
    }

    public boolean marketWide() {
        return marketWide;
    }

    public double totalPct() {
        return totalPct;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public int ticksLeft() {
        return ticksLeft;
    }

    public Decay decay() {
        return decay;
    }
}
