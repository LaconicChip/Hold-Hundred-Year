package com.holdhundredyear.market.quote;

import com.holdhundredyear.game.model.enums.SealState;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.DoubleUnaryOperator;

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

    /**
     * 无涨跌停标的（ETF，2026-10-25）：不封板、不拦成交、涨跌停价不下发。
     *
     * <p>ETF 的价格是"昨收 × 跟踪标的涨幅"算出来的，指数在快速模式下单日能走 ±20%，
     * 再给它套一层涨跌停只会让 ETF 跟不住标的、还会在 +10% 处误判成"封死买不进"。
     */
    private boolean limitless;

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
        return limitless || current < limitUpPrice();
    }

    /** 未钉死在跌停价（可卖；封板交易判定 Phase 6 用）。 */
    public boolean canSell() {
        return limitless || current > limitDownPrice();
    }

    /**
     * 把"今日口径"的价整体折算到新基准（除权/除息专用，由 CorporateActionService 调用）：
     * 昨收、当日开盘/最高/最低/现价、当日均价、分时缓冲**一次全部**过 mapper。
     *
     * <p>为什么必须一次做齐：除权只折 prevClose/current 的话，当日那根日K会留着除权前的
     * open/high 去配除权后的 close，图上凭空多出一根"腰斩大阴线"（超长上影），而 tooltip
     * 显示的是按除权后基准算出的真实日内涨跌幅（最多一个跌停），图和数字各说各话；分时线与
     * 均价线同理。漏一项就是错的，所以折算算法收在本类里，调用方只给映射。
     *
     * <p>mapper 是单调的（÷2 或 −派息额），所以折完 high 仍 >= max(open, current)、
     * low 仍 <= min(open, current)，不需要重排；均价对两种映射同样满足"平均值同映射"。
     */
    public void rebaseToday(DoubleUnaryOperator mapper) {
        prevClose = round2(mapper.applyAsDouble(prevClose));
        open = round2(mapper.applyAsDouble(open));
        high = round2(mapper.applyAsDouble(high));
        low = round2(mapper.applyAsDouble(low));
        current = round2(mapper.applyAsDouble(current));
        dayAvg = round2(mapper.applyAsDouble(dayAvg));
        // Deque 没有 replaceAll（那是 List 的方法），只能按原顺序重铺一份
        Deque<Double> rebased = new ArrayDeque<>(Math.max(4, intraday.size() * 2));
        for (Double price : intraday) {
            rebased.addLast(round2(mapper.applyAsDouble(price)));
        }
        intraday = rebased;
    }

    /** HALF_UP 保留 2 位：股票价格与涨跌停价共用，语义明确化用 BigDecimal。 */
    public static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** HALF_UP 保留 3 位：ETF 报价精度（1.00 的价位上 0.001 才跟得住指数）。 */
    public static double round3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
