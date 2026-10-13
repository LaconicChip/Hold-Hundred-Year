package com.holdhundredyear.game.constants;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 24 只股票静态档案（spec §4.1 化名股、§4.2 等权指数权重）。
 *
 * <p>取名口径：每只都影射本板块真实龙头或最有梗的那只票，但只用化名、不碰真名真代码；
 * 改名只改这一列字符串——板块、代码、发行价、β 与权重都不动（引擎标定与价格带是另一回事）。
 * 存量库的 stock_info 不会跟着变（WorldSeeder 用 INSERT IGNORE 播种），改完名要 UPDATE 一次。
 * id 即 stock_info 表主键（1..24），WorldSeeder 按本表顺序写入。
 */
public final class StockDefs {

    public static final List<StockDef> ALL = List.of(
            new StockDef(1L, "600001", "茅王酒业", "白酒", 168.00, 0.85, 1.0 / 24.0),
            new StockDef(2L, "600002", "寒武芯能", "半导体", 88.00, 1.50, 1.0 / 24.0),
            new StockDef(3L, "600003", "宁王新能", "新能源", 120.00, 1.15, 1.0 / 24.0),
            new StockDef(4L, "600004", "光茅绿能", "新能源", 26.00, 1.10, 1.0 / 24.0),
            new StockDef(5L, "600005", "恒瑞药堂", "医药", 45.00, 0.90, 1.0 / 24.0),
            new StockDef(6L, "600006", "万柯置业", "地产", 8.50, 0.95, 1.0 / 24.0),
            new StockDef(7L, "600007", "葵花银行", "银行", 6.00, 0.75, 1.0 / 24.0),
            new StockDef(8L, "600008", "东财证券", "券商", 18.00, 1.50, 1.0 / 24.0),
            new StockDef(9L, "600009", "平准保险", "保险", 52.00, 0.90, 1.0 / 24.0),
            new StockDef(10L, "600010", "迪王汽车", "新能源", 33.00, 0.95, 1.0 / 24.0),
            new StockDef(11L, "600011", "明珠电器", "家电", 66.00, 0.90, 1.0 / 24.0),
            new StockDef(12L, "600012", "獐岛水产", "水产", 12.00, 0.90, 1.0 / 24.0),
            new StockDef(13L, "600013", "神飞军工", "军工", 74.00, 0.95, 1.0 / 24.0),
            new StockDef(14L, "600014", "乐梦影业", "传媒", 22.00, 1.30, 1.0 / 24.0),
            new StockDef(15L, "600015", "曙光算力", "算力", 96.00, 1.55, 1.0 / 24.0),
            new StockDef(16L, "600016", "辉来超市", "零售", 15.00, 0.90, 1.0 / 24.0),
            new StockDef(17L, "600017", "国风航空", "航空", 9.50, 0.90, 1.0 / 24.0),
            new StockDef(18L, "600018", "煤茅能源", "煤炭", 11.00, 0.70, 1.0 / 24.0),
            new StockDef(19L, "600019", "玄铁重钢", "钢铁", 4.50, 0.90, 1.0 / 24.0),
            new StockDef(20L, "600020", "二师兄农牧", "农业", 17.00, 0.90, 1.0 / 24.0),
            new StockDef(21L, "600021", "通达快递", "物流", 38.00, 0.90, 1.0 / 24.0),
            new StockDef(22L, "600022", "谷子互娱", "传媒", 41.00, 1.30, 1.0 / 24.0),
            new StockDef(23L, "600023", "重机智造", "制造", 58.00, 0.95, 1.0 / 24.0),
            new StockDef(24L, "600024", "兴芯通讯", "通信", 7.50, 1.35, 1.0 / 24.0)
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
