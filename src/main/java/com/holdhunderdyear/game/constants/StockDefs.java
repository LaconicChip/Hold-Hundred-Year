package com.holdhunderdyear.game.constants;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 24 只股票静态档案（spec §4.1 化名股、§4.2 等权指数权重）。
 * id 即 stock_info 表主键（1..24），WorldSeeder 按本表顺序写入。
 */
public final class StockDefs {

    public static final List<StockDef> ALL = List.of(
            new StockDef(1L, "600001", "鸱夷酒业", "白酒", 168.00, 0.85, 1.0 / 24.0),
            new StockDef(2L, "600002", "片爷科技", "半导体", 88.00, 1.50, 1.0 / 24.0),
            new StockDef(3L, "600003", "卧龙新能", "新能源", 120.00, 1.15, 1.0 / 24.0),
            new StockDef(4L, "600004", "后羿光能", "新能源", 26.00, 1.10, 1.0 / 24.0),
            new StockDef(5L, "600005", "回春堂", "医药", 45.00, 0.90, 1.0 / 24.0),
            new StockDef(6L, "600006", "万间置业", "地产", 8.50, 0.95, 1.0 / 24.0),
            new StockDef(7L, "600007", "大通银号", "银行", 6.00, 0.75, 1.0 / 24.0),
            new StockDef(8L, "600008", "牛途证券", "券商", 18.00, 1.50, 1.0 / 24.0),
            new StockDef(9L, "600009", "安泰保险", "保险", 52.00, 0.90, 1.0 / 24.0),
            new StockDef(10L, "600010", "驰风汽车", "新能源", 33.00, 0.95, 1.0 / 24.0),
            new StockDef(11L, "600011", "巧手家电", "家电", 66.00, 0.90, 1.0 / 24.0),
            new StockDef(12L, "600012", "汪洋水产", "水产", 12.00, 0.90, 1.0 / 24.0),
            new StockDef(13L, "600013", "烽火军工", "军工", 74.00, 0.95, 1.0 / 24.0),
            new StockDef(14L, "600014", "极乐传媒", "传媒", 22.00, 1.30, 1.0 / 24.0),
            new StockDef(15L, "600015", "智算无穷", "算力", 96.00, 1.55, 1.0 / 24.0),
            new StockDef(16L, "600016", "阡陌商社", "零售", 15.00, 0.90, 1.0 / 24.0),
            new StockDef(17L, "600017", "凌云航空", "航空", 9.50, 0.90, 1.0 / 24.0),
            new StockDef(18L, "600018", "黑金能源", "煤炭", 11.00, 0.70, 1.0 / 24.0),
            new StockDef(19L, "600019", "百丈钢铁", "钢铁", 4.50, 0.90, 1.0 / 24.0),
            new StockDef(20L, "600020", "神农种业", "农业", 17.00, 0.90, 1.0 / 24.0),
            new StockDef(21L, "600021", "青鸟速运", "物流", 38.00, 0.90, 1.0 / 24.0),
            new StockDef(22L, "600022", "逍遥互娱", "传媒", 41.00, 1.30, 1.0 / 24.0),
            new StockDef(23L, "600023", "天工智造", "制造", 58.00, 0.95, 1.0 / 24.0),
            new StockDef(24L, "600024", "通天信联", "通信", 7.50, 1.35, 1.0 / 24.0)
    );

    /** 全部板块名（按声明顺序去重，共 21 个）：波动引擎 beginTick 按此顺序抽板块因子。 */
    public static final List<String> ALL_SECTORS = ALL.stream().map(StockDef::sector).distinct().toList();

    /** id → 档案（LinkedHashMap 保持声明顺序）：盘中引擎每跳按 id 查静态档案。 */
    private static final Map<Long, StockDef> BY_ID;
    static {
        Map<Long, StockDef> byId = new LinkedHashMap<>();
        for (StockDef def : ALL) {
            byId.put(def.id(), def);
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private StockDefs() {
    }

    public static Optional<StockDef> byCode(String code) {
        return ALL.stream().filter(s -> s.code().equals(code)).findFirst();
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
     * 单只股票静态定义。weight 为等权指数权重，beta 为个股对大盘的敏感度。
     */
    public record StockDef(long id, String code, String name, String sector, double basePrice, double beta, double weight) {
    }
}
