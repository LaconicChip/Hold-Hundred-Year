package com.holdhundredyear.market.quote;

import com.holdhundredyear.game.model.enums.SealState;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单只股票的内存行情（P9 内存权威）。可变 POJO：引擎每跳就地更新，读侧直接读字段。
 * 行情内部价格统一用 double（BigDecimal 只用于涨跌停价的舍入与展示层格式化）。
 *
 * <p>非线程安全：实例存活于 QuoteBoard 内部、由 per-user 世界锁保护（Phase 5），无内部同步。
 */
@Data
public class Quote {

    /** 分时缓冲容量：一交易日 90 个引擎跳，只存 price。 */
    private static final int INTRADAY_CAP = 90;

    private long stockId;
    private double prevClose;
    private double open;
    private double high;
    private double low;
    private double current;
    private int limitUpStreak;
    private boolean halted;
    private boolean isSt;

    /** 封板状态（Phase 6 封板机维护，restore 后恒为 NONE）。 */
    private SealState sealed = SealState.NONE;
    /** 封板剩余跳数（封板强度/炸板计时用，Phase 6）。 */
    private int sealTicksLeft;
    /** GARCH 波动放大系数，初值 1（Phase 3 引擎写入）。 */
    private double garchK = 1.0;

    /** 当日涨跌幅（比率）：结算写入当日收盘涨跌幅，盘中为 null；收盘态展示用
     *  （prev_close 双写后 dayChangePct() 恒为 0，见 SettlementService）。 */
    private Double lastClosePct;

    /** 当日均价：增量维护，分母为 tickCount。 */
    private double dayAvg;
    private int tickCount;

    /** 分时缓冲（cap 90，只存 price）；toString 排除，避免日志倾倒 90 个值。 */
    @ToString.Exclude
    private Deque<Double> intraday = new ArrayDeque<>();

    /**
     * 本局涨跌停幅度（普通股）：由 {@link com.holdhundredyear.game.model.enums.GameMode}
     * 在行情板恢复时统一写入，默认 10% 与历史行为一致；快速模式为 20%。
     * ST 股取其一半（默认 5%、快速 10%），所以 ST 戴帽摘帽不需要回来改这个字段。
     */
    private double limitPct = 0.10;

    /** 涨停价：按本局幅度（ST 减半）上浮 prevClose，HALF_UP 保留 2 位。 */
    public double limitUpPrice() {
        return limitPrice(prevClose, effectiveLimitPct(), true);
    }

    /** 跌停价：按本局幅度（ST 减半）下调 prevClose，HALF_UP 保留 2 位。 */
    public double limitDownPrice() {
        return limitPrice(prevClose, effectiveLimitPct(), false);
    }

    /**
     * 涨跌停价的唯一算法：prevClose × (1 ± pct)，HALF_UP 保留 2 位。
     * 行情展示（下发给前端的涨停/跌停虚线）与封板机共用此方法，
     * 避免"引擎按本局模式、前端按写死 10%"两套口径（快速模式曾因此画错线）。
     */
    public static double limitPrice(double prevClose, double pct, boolean up) {
        return round2(prevClose * (up ? 1 + pct : 1 - pct));
    }

    /** 当前生效的涨跌停幅度：ST 股取一半。 */
    public double effectiveLimitPct() {
        return isSt ? limitPct / 2 : limitPct;
    }

    /** 当日涨跌幅（比例，0.04 = +4%）。 */
    public double dayChangePct() {
        return (current - prevClose) / prevClose;
    }

    /**
     * 追加一个分时点：更新 current/high/low，以 tickCount 为分母增量维护 dayAvg，
     * 分时缓冲超 90 点时淘汰最旧。restore 后从第 1 跳起重新积累（快照不存分时）。
     */
    public void appendTick(double price) {
        current = price;
        high = Math.max(high, price);
        low = Math.min(low, price);
        dayAvg = (dayAvg * tickCount + price) / (tickCount + 1);
        tickCount++;
        intraday.addLast(price);
        if (intraday.size() > INTRADAY_CAP) {
            intraday.removeFirst();
        }
    }

    /** 未钉死在涨停价（可买；封板交易判定 Phase 6 用）。 */
    public boolean canBuy() {
        return current < limitUpPrice();
    }

    /** 未钉死在跌停价（可卖；封板交易判定 Phase 6 用）。 */
    public boolean canSell() {
        return current > limitDownPrice();
    }

    /** HALF_UP 保留 2 位：涨跌停价与展示共用，语义明确化用 BigDecimal。 */
    public static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
