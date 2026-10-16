package com.holdhundredyear.game.constants;

import com.holdhundredyear.game.model.enums.VolRegime;

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

    /**
     * 调度器心跳毫秒：远快于任何模式的跳间隔（100ms 对 500ms），否则"到点判定"本身
     * 就有半秒级的量化误差，实际跳距会被拉长、倒计时看起来会突然跳几秒。心跳只做一次
     * 内存预判（没到点的账户不抢锁、不查库），到点才真正推进一跳，
     * 因此多模式并存时互不影响，快进与测试仍直接调 tickOne、不受墙钟约束。
     */
    public static final int SCHEDULER_HEARTBEAT_MS = 100;

    // ---- 三层因子基准波动（spec §5.2，每跳 σ）----
    /**
     * 个股特质波动（每跳）。2026-10-16 重标定：0.0035 → 0.0017。
     *
     * <p>旧值下整局实测<b>日收益标准差 8.0%、55% 的股票天收在涨跌停板上</b>（真实 A 股约
     * 2% 与 1%~2%），等于"每天一半时间在板上"，玩家形容为"不是涨停就是跌停"。标定验收见
     * {@code VolatilityDistributionTest}（跑完整局量分布，不是纸面推算）。
     */
    public static final double SIGMA_STOCK = 0.0017;
    /** 市场因子波动（每跳）：与个股同比例收敛，保住"齐涨齐跌"的相对强度。 */
    public static final double SIGMA_MKT = 0.0009;
    /** 板块因子波动（每跳）。 */
    public static final double SIGMA_SEC = 0.0006;

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
    /**
     * 每跳跳跃概率。2026-10-16 重标定：0.04 → 0.005。
     *
     * <p>旧值等于<b>每股每天期望 3.6 次跳跃</b>（真实盘面一天有一次像样脉冲就算多了），
     * 跳跃由此从"事件"退化成"背景噪声"，日收益被它推着走。现在每股每天约 0.45 次，
     * 单次幅度也放大（见 JUMP_K_*），让"跳"重新变成看得出来的事，而不是天天有。
     */
    public static final double JUMP_PROB = 0.005;
    /** 跳跃强度下限（× SIGMA_STOCK）：SIGMA_STOCK 降了，倍数补回来，单次跳跃仍是 0.9%~1.5% 量级。 */
    public static final int JUMP_K_MIN = 5;
    /** 跳跃强度上限（× SIGMA_STOCK）。 */
    public static final int JUMP_K_MAX = 9;

    // ---- GARCH 波动聚集 ----
    /** 衰减系数。 */
    public static final double GARCH_DECAY = 0.85;
    /** 新信息权重。 */
    public static final double GARCH_WEIGHT = 0.15;
    /**
     * |r|/σ 截断上限。2026-10-16 重标定：4.0 → 1.8。
     *
     * <p>旧上限让"一次大跳 → 之后十几跳 σ 放大 4 倍"成为必然：一根 1.5% 的单跳就把系数顶满，
     * 于是每次脉冲后面都跟着一条直奔涨跌停的斜线（玩家说的"平稳一会儿然后拉涨停"）。
     * 1.8 保留"波动聚集"这个真实特征，但不再让一次跳跃决定全天的命运。
     */
    public static final double GARCH_CAP = 1.8;

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
    /**
     * 日内均值回归强度（每跳，向当日均价回拉的系数）。2026-10-16 新增，见
     * {@link com.holdhundredyear.market.engine.VolatilityEngine#rawReturn}。
     *
     * <p>纯随机游走只有"越走越远"，没有"涨多了回一下"，所以分时图像一条斜线而不是一团抖动。
     * 往当日均价回拉才有真实分时那种围绕均价来回震荡的手感；0.06 是实测出来的：再大就把
     * 日内趋势抹平（板块行情演不出来），再小就没有抖动。
     */
    public static final double OU_KAPPA = 0.06;

    /**
     * 纪元漂移统一标定系数：{@link com.holdhundredyear.game.constants.EraDefs} 里的
     * muMkt 与板块系数写的是<b>相对强度</b>（哪个纪元热、哪个板块被捧），落到每跳漂移前
     * 统一乘这个系数。
     *
     * <p>为什么要有这一层：旧档案的绝对值是"最热板块每跳 +0.30%"，90 跳一天就是<b>单日
     * +27% 的板块漂移</b>，全市场在牛市纪元每天 +7%~11%——涨跌停板根本拦不住，只能天天贴板。
     * 保留相对强度、只缩绝对值，纪元之间的性格差异一点没丢。
     */
    public static final double ERA_DRIFT_SCALE = 0.055;

    /**
     * 触发磁吸的涨跌幅阈值。2026-10-16 重标定：0.065 → 0.08。
     *
     * <p>这条是"满屏涨停"真正的推手：旧阈值下只要当天涨过 6.5%，就开始每跳 +0.15% 的单向
     * 漂移——上午 10 点半涨到 6.5% 的话，剩下 40 跳能再推 6%，等于<b>一旦摸到 6.5% 就必然
     * 收在涨停板上</b>。磁吸效应本该是"越靠近板越难出去"，不该是"过线就自动上板"，所以把
     * 触发线抬到 8%：离板 2% 以内才吸，快收盘才涨过线的票也就吸几跳，不至于凭空多出一批板。
     */
    public static final double MAGNET_TRIGGER = 0.08;
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

    // ---- 高利贷（spec §6.6：场外配资，风险自担；不入担保比例，但入净资产）----
    /** 高利贷额度上限：净资产的 5 倍（融资是 1 倍），借满即 6 倍仓位——
     *  单个交易日跌 16.7% 就资不抵债，这就是"险恶"两个字的数值化。 */
    public static final double SHARK_LIMIT_RATIO = 5.0;
    /** 起始年化 24%：日息 = 年化 ÷ 本局年交易日数 → 标准 1%/天、快速 2%/天。 */
    public static final double SHARK_ANNUAL_BASE = 0.24;
    /** 账龄每满 SHARK_RATE_STEP_DAYS 个计息日，年化再上调一档。 */
    public static final double SHARK_ANNUAL_STEP = 0.12;
    /** 升档步长（交易日）。 */
    public static final int SHARK_RATE_STEP_DAYS = 5;
    /** 年化封顶 72%：标准 3%/天、快速 6%/天。 */
    public static final double SHARK_ANNUAL_MAX = 0.72;
    /** 借款期限 = 本局一个游戏年（标准 24 个交易日、快速 12 个），到期必须还。 */
    public static final int SHARK_TERM_YEARS = 1;

    /** 熔断阈值：指数日内自昨收跌幅（spec §3/§4.4）。 */
    public static final double CIRCUIT_BREAKER_DROP = 0.07;
    /** 指数基期值：无前日快照时日收益评价的指数基准（spec §3 结算⑥）。 */
    public static final double INITIAL_INDEX = 1000.0;

    private GameConstants() {
    }
}
