package com.holdhunderdyear.game.content;


import com.holdhunderdyear.game.content.ContentCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股吧弹幕文案池（spec §7.3）：9 个全局类别池 + 21 个板块池。
 *
 * <p>文案本体在 <code>resources/content/danmu.txt</code>，这里只是一层带类型的读取入口——
 * 加段子、改措辞改那个文本文件即可，不必动 Java。板块名必须与 {@link com.holdhunderdyear.game.constants.StockDefs} 的
 * sector 字段一一对应（由 DanmuTextsTest/StockPersonasTest 校验）。
 *
 * <p>三级混抽（个股专属 → 板块 → 全局）在 {@code DanmuService}；个股专属池见
 * {@link StockPersonas}。全部文案纯中文、无 emoji。
 */
public final class DanmuTexts {

    private static final String FILE = "danmu";
    private static final String SECTOR_PREFIX = "SECTOR:";

    /** 指数大跌（日内跌幅 ≤ -2%）。 */
    public static final List<String> INDEX_DROP = ContentCatalog.require(FILE, "INDEX_DROP");
    /** 指数大涨（日内涨幅 ≥ +2%）。 */
    public static final List<String> INDEX_RISE = ContentCatalog.require(FILE, "INDEX_RISE");
    /** 涨停封板（sealed=UP）。 */
    public static final List<String> LIMIT_UP = ContentCatalog.require(FILE, "LIMIT_UP");
    /** 跌停封板（sealed=DOWN）。 */
    public static final List<String> LIMIT_DOWN = ContentCatalog.require(FILE, "LIMIT_DOWN");
    /** 急拉（|涨跌幅| > 5% 且为正）。 */
    public static final List<String> RALLY = ContentCatalog.require(FILE, "RALLY");
    /** 跳水（|涨跌幅| > 5% 且为负）。 */
    public static final List<String> DIVE = ContentCatalog.require(FILE, "DIVE");
    /** 次级急拉（3%~5%）。 */
    public static final List<String> RALLY_MINOR = ContentCatalog.require(FILE, "RALLY_MINOR");
    /** 次级跳水（3%~5%）。 */
    public static final List<String> DIVE_MINOR = ContentCatalog.require(FILE, "DIVE_MINOR");
    /** 横盘冷门（全部活跃股 |涨跌幅| < 1%）。 */
    public static final List<String> FLAT = ContentCatalog.require(FILE, "FLAT");

    /** 全局类别池：类别名 -> 弹幕池，就是上面那 9 个常量的汇总视图（供计数与遍历）。 */
    static final Map<String, List<String>> GLOBAL_POOLS = globalPools();

    /** 板块池：key 为板块名（与 StockDefs.sector 同集合），value 为该板块的股吧口吻短评。 */
    public static final Map<String, List<String>> SECTOR_FLAVOR = sectorFlavor();

    /** 全部全局类别的文案合计（供测试统计总量）。 */
    public static int totalCount() {
        int total = 0;
        for (List<String> pool : GLOBAL_POOLS.values()) {
            total += pool.size();
        }
        return total;
    }

    private DanmuTexts() {
    }

    /** 按板块取池子，未知板块回退全局横盘池（绝不返回空，调用方直接抽）。 */
    public static List<String> sectorPool(String sector) {
        List<String> pool = SECTOR_FLAVOR.get(sector);
        return pool != null && !pool.isEmpty() ? pool : FLAT;
    }

    private static Map<String, List<String>> globalPools() {
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        byCategory.put("INDEX_DROP", INDEX_DROP);
        byCategory.put("INDEX_RISE", INDEX_RISE);
        byCategory.put("LIMIT_UP", LIMIT_UP);
        byCategory.put("LIMIT_DOWN", LIMIT_DOWN);
        byCategory.put("RALLY", RALLY);
        byCategory.put("DIVE", DIVE);
        byCategory.put("RALLY_MINOR", RALLY_MINOR);
        byCategory.put("DIVE_MINOR", DIVE_MINOR);
        byCategory.put("FLAT", FLAT);
        return Map.copyOf(byCategory);
    }

    private static Map<String, List<String>> sectorFlavor() {
        Map<String, List<String>> bySector = new LinkedHashMap<>();
        for (String key : ContentCatalog.sectionsWithPrefix(FILE, SECTOR_PREFIX)) {
            bySector.put(key.substring(SECTOR_PREFIX.length()), ContentCatalog.require(FILE, key));
        }
        return Map.copyOf(bySector);
    }
}
