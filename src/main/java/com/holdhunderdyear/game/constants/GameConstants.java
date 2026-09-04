package com.holdhunderdyear.game.constants;

import com.holdhunderdyear.game.model.enums.VolRegime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游戏数值常量集中地（spec §5/§6/§7）。
 * 波动引擎（Phase 4）与交易/融资（Phase 6/7）从这里取值，禁止魔法数字散落。
 */
public final class GameConstants {

    // ---- 引擎节奏（spec §5.1）----
    /** 每半场跳数（上午/下午各 45）。 */
    public static final int TICKS_PER_SESSION = 45;
    /** 一交易日总跳数。 */
    public static final int TOTAL_TICKS = 90;
    /** 引擎跳间隔毫秒。 */
    public static final int TICK_MS = 3000;
    /** 午休时长毫秒。 */
    public static final int LUNCH_MS = 30000;

    // ---- 三层因子基准波动（spec §5.2，每跳 σ）----
    /** 个股特质波动。 */
    public static final double SIGMA_STOCK = 0.0035;
    /** 市场因子波动。 */
    public static final double SIGMA_MKT = 0.0018;
    /** 板块因子波动。 */
    public static final double SIGMA_SEC = 0.0012;

    /** 波动状态乘子（不可变，按 CALM/NORMAL/WILD 保序）。 */
    public static final Map<VolRegime, Double> VOL_REGIME;
    static {
        Map<VolRegime, Double> regimes = new LinkedHashMap<>();
        regimes.put(VolRegime.CALM, 0.6);
        regimes.put(VolRegime.NORMAL, 1.0);
        regimes.put(VolRegime.WILD, 2.0);
        VOL_REGIME = Collections.unmodifiableMap(regimes);
    }

    // ---- 稀疏跳跃 ----
    /** 每跳跳跃概率。 */
    public static final double JUMP_PROB = 0.04;
    /** 跳跃强度下限（× SIGMA_STOCK）。 */
    public static final int JUMP_K_MIN = 3;
    /** 跳跃强度上限（× SIGMA_STOCK）。 */
    public static final int JUMP_K_MAX = 5;

    // ---- GARCH 波动聚集 ----
    /** 衰减系数。 */
    public static final double GARCH_DECAY = 0.85;
    /** 新信息权重。 */
    public static final double GARCH_WEIGHT = 0.15;
    /** |r|/σ 截断上限。 */
    public static final double GARCH_CAP = 4.0;

    // ---- 日内 U 型 ----
    /** 开盘波动（tick 0）。 */
    public static final double U_OPEN = 1.6;
    /** 开盘段末端（tick 10，与 U_OPEN 线性过渡）。 */
    public static final double U_OPEN_END = 0.9;
    /** 盘中波动（tick 11..79）。 */
    public static final double U_MID = 0.8;
    /** 尾盘波动（tick 80..89）。 */
    public static final double U_CLOSE = 1.4;

    // ---- 磁吸效应（Phase 6 封板机用，先立常量）----
    /** 触发磁吸的涨跌幅阈值（距涨停/跌停 6.5% 以内）。 */
    public static final double MAGNET_TRIGGER = 0.065;
    /** 磁吸漂移（每跳向涨跌停方向 0.15%）。 */
    public static final double MAGNET_DRIFT = 0.0015;
    /** 连板磁吸强度加成系数（仅涨停方向）。 */
    public static final double STREAK_MAGNET_BONUS = 0.5;
    /** 连板磁吸加成上限（连板数封顶）。 */
    public static final int STREAK_MAGNET_CAP = 4;

    // ---- 封板/炸板（Phase 6 封板机用，先立常量）----
    /** 封板基础概率。 */
    public static final double SEAL_BASE_PROB = 0.06;
    /** 连板奖励概率（每多一连板 +2%）。 */
    public static final double SEAL_STREAK_BONUS = 0.02;
    /** 炸板概率。 */
    public static final double BREAK_PROB = 0.35;
    /** 封板最少跳数。 */
    public static final int SEAL_MIN_TICKS = 5;
    /** 封板最多跳数。 */
    public static final int SEAL_MAX_TICKS = 30;
    /** 炸板冲击下限。 */
    public static final double BREAK_SHOCK_MIN = 0.02;
    /** 炸板冲击上限。 */
    public static final double BREAK_SHOCK_MAX = 0.04;
    /** 一字板分时最小间隔（封板期间分时价与封板价的容差）。 */
    public static final double ONE_WORD_GAP = 0.10;
    /** 触发位越近封板概率越高：每超触发点 1% 加 0.02。 */
    public static final double PROXIMITY_SEAL_SCALE = 2.0;

    // ---- 交易/融资（Phase 6/7 用，先立常量；金额计算层用 BigDecimal，比率统一 double）----
    /** 一手股数。 */
    public static final int LOT_SIZE = 100;
    /** 初始资金。 */
    public static final double INITIAL_BALANCE = 100_000.00;
    /** 佣金率（万 3，spec §6.2 权威）。 */
    public static final double COMMISSION_RATE = 0.0003;
    /** 单笔最低佣金（元）。 */
    public static final double COMMISSION_MIN = 5.0;
    /** 印花税率（卖出，千 1）。 */
    public static final double STAMP_TAX_RATE = 0.001;
    /** 最高融资比例（自有资金的 1 倍）。 */
    public static final double BORROW_LIMIT_RATIO = 1.0;
    /** 追保线（维持担保比例）。 */
    public static final double MARGIN_CALL_RATIO = 1.4;
    /** 平仓线。 */
    public static final double LIQUIDATION_RATIO = 1.2;
    /** 平仓后目标担保比例。 */
    public static final double LIQUIDATION_TARGET = 1.3;
    /** 融资年化利率。 */
    public static final double ANNUAL_INTEREST = 0.08;
    /** 一游戏年交易日数。 */
    public static final int DAYS_PER_YEAR = 36;

    private GameConstants() {
    }
}
