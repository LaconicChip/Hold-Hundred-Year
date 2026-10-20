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
     * 个股特质波动（每跳）。2026-10-20 引擎大改：0.0016 → 0.0050。
     *
     * <p>三次标定的账：0.0035 → 满屏涨停（日 σ 8.0%、55% 的股票天收在板上）→ 0.0017/0.0016
     * → 波动太小（实测日 σ 3.0~3.2%，玩家反馈"没感觉"）。这次把 0.0016 抬到 0.0050：
     * 有机日 σ 落到 4.5%~5.5%（用户 2026-10-20 明确选的"够刺激"档），贴板率另有闸门管住。
     *
     * <p>注意这是<b>基准</b>值，实际每只票乘自己的 {@code volMult}（0.55~1.70），
     * 所以银行/煤炭是死水、算力/券商是疯票；ST 票再乘 {@link #ST_VOL_FACTOR} 收窄
     * （±5% 的板很近，ST 还按普通波动跑就会天天顶在板上）。验收见
     * {@code VolatilityDistributionTest}（跑完整局量分布，不是纸面推算）。
     */
    public static final double SIGMA_STOCK = 0.0038;
    /**
     * 市场因子波动（每跳）。2026-10-20 引擎大改：0.0009 → 0.0013。
     *
     * <p>个股特质 σ 抬了 3 倍多之后，市场因子的相对分量被稀释，"大盘齐涨齐跌"的手感会变淡
     * （同板块两票的相关差从 0.10 掉到 0.03，板块联动几乎看不出来）。所以把市场与板块因子
     * 同步抬一档，保住"大盘带着走、板块一起动"这个 A 股体感——但冲击落地时按个股敏感度分化
     * （{@code StockDef.policyBeta}），不是 24 只票被砸同一个数。
     */
    public static final double SIGMA_MKT = 0.0013;
    /** 板块因子波动（每跳）。2026-10-20 引擎大改：0.0006 → 0.0020（同上，保住板块联动）。 */
    public static final double SIGMA_SEC = 0.0020;

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
    public static final double JUMP_PROB = 0.007;
    /**
     * 跳跃强度下限（× 该股当跳 σ）。2026-10-20 引擎大改：5 → 4。
     *
     * <p>跳跃幅度跟<b>该股自己的 σ</b> 走（不是全局基准），所以银行跳得轻、妖股跳得重；
     * 倍数从 5~9 降到 4~6，是因为 σ 抬了 3 倍多——照旧 5~9 倍就是单跳 4% 的巨跳，
     * 一次跳跃直接把这只票送上涨停板，贴板率会失控。
     */
    public static final int JUMP_K_MIN = 5;
    /** 跳跃强度上限（× 该股当跳 σ）。 */
    public static final int JUMP_K_MAX = 8;

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
    /**
     * 全市场共性的日内波动曲线（2026-10-20 引擎大改，从 1.25/1.0/0.95/1.15 压到 1.12/1.02/1.0/1.08）。
     *
     * <p>只作用于<b>市场因子与板块因子</b>：所有票共享同一条曲线，"开盘与尾盘略活跃"这个
     * A 股共性保留，但幅度刻意温和——旧版 1.6/0.8/1.4 会让玩家看到"开局动一下、中间几乎平盘、
     * 收盘再动一下"。个股特质项另走 {@link #U_PHASE_AMP} 的相位调制，两者是乘法关系。
     */
    public static final double U_OPEN = 1.12;
    /** 开盘段末端（tick 10，与 U_OPEN 线性过渡）。 */
    public static final double U_OPEN_END = 1.02;
    /** 盘中波动（tick 11..79）。 */
    public static final double U_MID = 1.0;
    /** 尾盘波动（tick 80..89）。 */
    public static final double U_CLOSE = 1.08;

    /**
     * 个股日内相位振幅（2026-10-20 引擎大改新增）。
     *
     * <p>每只票一天里有一条自己的"活跃包络"：σ 在相位点最高（1+amp），相位点后 45 跳最低
     * （1−amp），相位由 {@code StockDef.volPhase} 决定，24 只票互不相同。玩家反馈的
     * "都会在固定时间突然大涨或者大跌一轮"，根因就是旧版 24 只票共用同一条 U 型曲线——
     * 波动永远在同样的时段整体放大。现在改成"各股各节奏"：银行的活跃段在尾盘、
     * 券商在开盘、光伏在午后，全市场不再有固定的暴动时刻。
     */
    public static final double U_PHASE_AMP = 0.22;

    /**
     * ST 票的波动收窄系数（2026-10-20 引擎大改新增）。
     *
     * <p>ST 的涨跌停只有 ±5%，而本局的日 σ 抬到了 5% 上下——ST 票若按普通波动跑，
     * 盘中摸到 ±4% 的概率很高、天天顶在板上（现实里 ST 股恰恰是波动很小的品种，
     * 因为价格被窄幅限制住）。所以戴帽后按 0.55 收一档，摘帽自动恢复。
     */
    public static final double ST_VOL_FACTOR = 0.55;

    // ---- 磁吸效应（Phase 6 封板机用，先立常量）----
    /**
     * 日内均值回归强度（每跳，向当日均价回拉的系数）。2026-10-16 新增，
     * 见 {@link com.holdhundredyear.market.engine.VolatilityEngine#rawReturn}。
     *
     * <p>纯随机游走只有"越走越远"，没有"涨多了回一下"，所以分时图像一条斜线而不是一团抖动。
     * 往当日均价回拉才有真实分时那种围绕均价来回震荡的手感。
     *
     * <p>2026-10-20 引擎大改：0.06 → 0.035。原因是这个力是<b>随偏离变大而变强</b>的：
     * 开盘几跳均价几乎等于现价、几乎不拉，越到收盘偏离越大、拉得越狠，于是收盘前的波动被
     * 系统性压平（实测日内方差从开盘往收盘一路衰减，"爆发起点"有 38% 挤在开盘那一段）。
     * 调小后回归仍看得见（偏离 3% 时每跳拉 0.1%，相对单跳 σ 0.4% 仍有手感），
     * 但不再把下午压成直线，日内的形状交给每只票自己的相位。
     */
    public static final double OU_KAPPA = 0.035;

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
     * 触发磁吸的涨跌幅阈值。2026-10-16 重标定 0.065 → 0.08；2026-10-20 引擎大改再抬到 0.09。
     *
     * <p>这条是"满屏涨停"真正的推手：旧阈值下只要当天涨过 6.5%，就开始每跳 +0.15% 的单向
     * 漂移——上午 10 点半涨到 6.5% 的话，剩下 40 跳能再推 6%，等于<b>一旦摸到 6.5% 就必然
     * 收在涨停板上</b>。磁吸效应本该是"越靠近板越难出去"，不该是"过线就自动上板"，所以把
     * 触发线抬到 8%：离板 2% 以内才吸，快收盘才涨过线的票也就吸几跳，不至于凭空多出一批板。
     *
     * <p>2026-10-20 再抬到 9% 并改成<b>按贴近度增强</b>：日 σ 抬到 5% 上下后，盘中摸到 8% 的票
     * 会多得多，旧版"一过线就给恒定漂移"等于"只要摸到线就必然收在涨停板上"（实测有机贴板 16%，
     * 相当于每天 4 只票上板）。现在触发线处吸力为 0、越靠近板越强，只有真正贴到板边的票被按住。
     */
    public static final double MAGNET_TRIGGER = 0.09;
    /** 磁吸漂移（每跳向涨跌停方向的最大值，贴到板时达到，触发线处为 0）。 */
    public static final double MAGNET_DRIFT = 0.0012;
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
