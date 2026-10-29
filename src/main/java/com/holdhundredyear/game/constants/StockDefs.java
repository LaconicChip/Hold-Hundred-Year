package com.holdhundredyear.game.constants;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 24 只股票静态档案（spec §4.1 化名股、§4.2 等权指数权重）。
 *
 * <p>取名口径：每只都影射本板块真实龙头或最有梗的那只票，但只用化名、不碰真名真代码；
 * 改名只改这一列字符串——板块、代码、发行价、β 与权重都不动（引擎标定与价格带是另一回事）。
 * 存量库的 stock_info 不会跟着变（WorldSeeder 用 INSERT IGNORE 播种），改完名要 UPDATE 一次。
 * id 即 stock_info 表主键（1..24），WorldSeeder 按本表顺序写入。
 *
 * <p><b>个股特征三件套（2026-10-20 引擎大改，spec §5.2）</b>：玩家反馈"每只股票的波动节奏
 * 很像，都会在固定时间突然大涨或大跌一轮"，根因是 24 只票共用同一条 U 型曲线、同一档波动、
 * 而且 12 只票的 β 恰好都等于 0.90。现在每只票带三个静态特征：
 * <ul>
 *   <li>{@code volMult} 个股特质波动倍数：银行/煤炭 0.55~0.75（死水），题材妖股 1.4~1.7（疯）。</li>
 *   <li>{@code volPhase} 日内活跃相位（0~89 跳）：决定这只票一天里"哪一段最活跃"，
 *       24 只票的相位互不相同 → 不再是"全市场固定时间一起动"。</li>
 *   <li>{@code policyBeta} 政策/市场消息敏感度：降准降息这类全市场消息落地时，
 *       券商/地产反应大（1.3~1.5），银行/煤炭反应小（0.6~0.8）——大盘带动但按个股分化，
 *       而不是 24 只票一起跳同一个数。</li>
 * </ul>
 * 三个特征都是静态档案的一部分，不落库（运行时按 id 从本表查），所以不涉及改表。
 */
public final class StockDefs {

    public static final List<StockDef> ALL = List.of(
            new StockDef(1L, "600001", "贵州茅王", "白酒", 168.00, 0.72, 0.68, 2, 0.75, 1.0 / 24.0),
            new StockDef(2L, "600002", "寒武急", "半导体", 88.00, 1.48, 1.55, 9, 1.30, 1.0 / 24.0),
            new StockDef(3L, "600003", "宁得时代", "新能源", 120.00, 1.14, 1.28, 39, 1.20, 1.0 / 24.0),
            new StockDef(4L, "600004", "光茅绿能", "新能源", 26.00, 1.10, 1.22, 24, 1.15, 1.0 / 24.0),
            new StockDef(5L, "600005", "恒瑞药堂", "医药", 45.00, 0.94, 1.02, 13, 0.95, 1.0 / 24.0),
            new StockDef(6L, "600006", "万柯置业", "地产", 8.50, 1.06, 1.18, 50, 1.35, 1.0 / 24.0),
            new StockDef(7L, "600007", "招笑银行", "银行", 6.00, 0.62, 0.55, 79, 0.60, 1.0 / 24.0),
            new StockDef(8L, "600008", "东财证券", "券商", 18.00, 1.45, 1.62, 6, 1.50, 1.0 / 24.0),
            new StockDef(9L, "600009", "瓷器平安", "保险", 52.00, 0.74, 0.70, 28, 0.70, 1.0 / 24.0),
            new StockDef(10L, "600010", "比养迪", "新能源", 33.00, 1.02, 1.06, 31, 1.15, 1.0 / 24.0),
            new StockDef(11L, "600011", "明珠电器", "家电", 66.00, 0.70, 0.66, 35, 0.80, 1.0 / 24.0),
            new StockDef(12L, "600012", "獐岛水产", "水产", 12.00, 0.88, 1.12, 20, 0.90, 1.0 / 24.0),
            new StockDef(13L, "600013", "神飞军工", "军工", 74.00, 1.08, 1.10, 68, 1.20, 1.0 / 24.0),
            new StockDef(14L, "600014", "毛豆传媒", "传媒", 22.00, 1.28, 1.42, 61, 1.15, 1.0 / 24.0),
            new StockDef(15L, "600015", "瓷器曙光", "算力", 96.00, 1.52, 1.70, 46, 1.25, 1.0 / 24.0),
            new StockDef(16L, "600016", "永惠商超", "零售", 15.00, 0.80, 0.92, 42, 0.85, 1.0 / 24.0),
            new StockDef(17L, "600017", "瓷器国航", "航空", 9.50, 0.90, 1.04, 17, 1.00, 1.0 / 24.0),
            new StockDef(18L, "600018", "瓷器神华", "煤炭", 11.00, 0.65, 0.58, 10, 0.62, 1.0 / 24.0),
            new StockDef(19L, "600019", "宝矿控股", "钢铁", 4.50, 0.86, 0.96, 54, 0.88, 1.0 / 24.0),
            new StockDef(20L, "600020", "亩原农牧", "农业", 17.00, 0.82, 1.08, 25, 0.92, 1.0 / 24.0),
            new StockDef(21L, "600021", "顺风快配", "物流", 38.00, 0.76, 0.74, 75, 0.85, 1.0 / 24.0),
            new StockDef(22L, "600022", "谷子互娱", "传媒", 41.00, 1.30, 1.45, 22, 1.12, 1.0 / 24.0),
            new StockDef(23L, "600023", "三力智造", "制造", 58.00, 0.98, 1.00, 38, 1.05, 1.0 / 24.0),
            new StockDef(24L, "600024", "中星通讯", "通信", 7.50, 1.35, 1.50, 72, 1.20, 1.0 / 24.0)
    );

    /** 全部板块名（按声明顺序去重，共 21 个）：波动引擎 beginTick 按此顺序抽板块因子。 */
    public static final List<String> ALL_SECTORS = ALL.stream().map(StockDef::sector).distinct().toList();

    /**
     * 三只 ETF（2026-10-25 新增）：给"不会选股"的玩家一条买指数的退路。
     *
     * <p>ETF 不是第 25~27 只股票：它们不参与指数权重（weight=0）、不吃板块因子与事件冲击
     * （beta/policyBeta/volMult 全 0），价格每跳由跟踪标的折算出来——大盘 ETF 跟百年指数，
     * 科创 ETF 跟半导体+算力+通信，新能源 ETF 跟三只新能源股。
     *
     * <p>三只发行价统一 1.00 元：ETF 按 0.001 报价（真实 A 股 ETF 就是三位小数），
     * 1.00 的价位上最小变动 0.001 = 0.1%，才跟得住每跳约 0.05% 的指数波动。
     *
     * <p>id 从 25 起、与 24 只股票分开，是为了让所有"按 StockDefs.ALL 遍历"的既有逻辑
     * （事件池、主力剧本、NPC 荐股、涨停板洗牌、股吧人设）天然只认股票；凡是走
     * {@code board.getQuotes()} 的地方则显式用 {@link #isEtf(long)} 分流。
     */
    public static final List<StockDef> ETFS = List.of(
            new StockDef(25L, "510100", "百年大盘ETF", "ETF", 1.00, 0.0, 0.0, 0, 0.0, 0.0),
            new StockDef(26L, "588100", "科创百年ETF", "ETF", 1.00, 0.0, 0.0, 0, 0.0, 0.0),
            new StockDef(27L, "516100", "新能源ETF", "ETF", 1.00, 0.0, 0.0, 0, 0.0, 0.0)
    );

    /** 大盘 ETF：直接按百年指数折算（发行价 1.00 元对应 1000 点）。 */
    public static final long ETF_MARKET = 25L;
    /** 科创 ETF（半导体 / 算力 / 通信 等权篮子）。 */
    public static final long ETF_TECH = 26L;
    /** 新能源 ETF（新能源三只等权篮子）。 */
    public static final long ETF_NEW_ENERGY = 27L;

    /** 跟踪篮子：ETF id → 成分股 id（等权）。大盘 ETF 不在此表，走指数。 */
    public static final Map<Long, List<Long>> ETF_BASKETS = Map.of(
            26L, List.of(2L, 15L, 24L),   // 科创：寒武急（半导体）/ 瓷器曙光（算力）/ 中星通讯（通信）
            27L, List.of(3L, 4L, 10L)     // 新能源：宁得时代 / 光茅绿能 / 比养迪
    );

    /** 全部 ETF id（行情板分流用，避免每跳装箱 Stream 判断）。 */
    public static final Set<Long> ETF_IDS = ETFS.stream().map(StockDef::id)
            .collect(Collectors.toUnmodifiableSet());

    /** 股票 + ETF：播种、行情板恢复与"这只标的存在吗"校验用的全集。 */
    public static final List<StockDef> ALL_INSTRUMENTS =
            Stream.concat(ALL.stream(), ETFS.stream()).toList();

    /** id → 档案（LinkedHashMap 保持声明顺序）：盘中引擎每跳按 id 查静态档案。 */
    private static final Map<Long, StockDef> BY_ID;
    static {
        Map<Long, StockDef> byId = new LinkedHashMap<>();
        for (StockDef def : ALL_INSTRUMENTS) {
            byId.put(def.id(), def);
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private StockDefs() {
    }

    public static Optional<StockDef> byCode(String code) {
        return ALL_INSTRUMENTS.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    /** 该 id 是不是 ETF（价格由跟踪标的折算，不参与指数、事件、封板与停牌核查）。 */
    public static boolean isEtf(long stockId) {
        return ETF_IDS.contains(stockId);
    }

    /** 按 id 查静态档案；未知 id 属编程错误，直接抛。 */
    public static StockDef byId(long id) {
        StockDef def = BY_ID.get(id);
        if (def == null) {
            throw new IllegalArgumentException("Unknown stockId: " + id);
        }
        return def;
    }

    /**
     * 单只股票静态定义。
     *
     * @param weight 等权指数权重
     * @param beta 对市场因子的敏感度（大盘共振强度）
     * @param volMult 个股特质波动倍数（本票的"脾气"：银行死水、妖股疯）
     * @param volPhase 日内活跃相位（0~89 跳，本票一天里最活跃的那一段在哪儿）
     * @param policyBeta 政策/市场消息敏感度（全市场消息落地时本票反应多大）
     */
    public record StockDef(long id, String code, String name, String sector, double basePrice, double beta,
                           double volMult, int volPhase, double policyBeta, double weight) {
    }
}
