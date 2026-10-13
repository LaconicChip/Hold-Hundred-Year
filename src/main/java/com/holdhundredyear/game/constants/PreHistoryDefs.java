package com.holdhundredyear.game.constants;

import java.util.Map;

/**
 * 开局前 10 个交易日的板块走势档案（个股日K的"前世"）。
 *
 * <p>注册时给每只票铺 10 根历史日K（{@code stock_daily_bar}，game_day 0..-9），玩家第 1 天
 * 点开个股页就能看到"这只票最近是被怎么对待的"，而不是一片空白。走势只按板块档案派生，
 * 不参与本局的昨收、指数基期与结算。
 *
 * <p>{@code dailyDrift} 是日均涨跌幅（比率，0.01 = 每个交易日 +1%），{@code dailyVol} 是日
 * 波动标准差。取值化用各板块在游戏背景前那一段真实行情的形状（化用，不搬真实数据）：
 * 券商/算力/传媒/煤炭前史走强（钱先到），地产/钢铁/医药/半导体/通信前史走弱（先去杠杆的是它们），
 * 银行/家电几乎不动（低波动红利），水产最惨——它的绰号就叫"扇贝跑了"。
 */
public final class PreHistoryDefs {

    /** 前史根数：10 根日K，够看清趋势，又不至于让玩家以为开局前已经打了半年工。 */
    public static final int BARS = 10;

    /** 单个板块的前史走势：日均涨跌幅 + 日波动标准差（都是比率口径）。 */
    public record Profile(double dailyDrift, double dailyVol) {
        public Profile {
            if (dailyVol <= 0) {
                throw new IllegalArgumentException("dailyVol must be > 0, got " + dailyVol);
            }
        }
    }

    private static final Profile FLAT = new Profile(0.0, 0.012);

    private static final Map<String, Profile> BY_SECTOR = Map.ofEntries(
            Map.entry("白酒", new Profile(-0.003, 0.012)),
            Map.entry("半导体", new Profile(-0.008, 0.022)),
            Map.entry("新能源", new Profile(-0.002, 0.018)),
            Map.entry("医药", new Profile(-0.006, 0.015)),
            Map.entry("地产", new Profile(-0.012, 0.020)),
            Map.entry("银行", new Profile(0.002, 0.006)),
            Map.entry("券商", new Profile(0.008, 0.015)),
            Map.entry("保险", new Profile(0.001, 0.008)),
            Map.entry("家电", new Profile(0.003, 0.010)),
            Map.entry("水产", new Profile(-0.020, 0.030)),
            Map.entry("军工", new Profile(0.004, 0.020)),
            Map.entry("传媒", new Profile(0.012, 0.025)),
            Map.entry("算力", new Profile(0.015, 0.025)),
            Map.entry("零售", new Profile(-0.001, 0.012)),
            Map.entry("航空", new Profile(-0.005, 0.015)),
            Map.entry("煤炭", new Profile(0.010, 0.015)),
            Map.entry("钢铁", new Profile(-0.007, 0.015)),
            Map.entry("农业", new Profile(0.006, 0.018)),
            Map.entry("物流", new Profile(-0.004, 0.012)),
            Map.entry("制造", new Profile(0.003, 0.012)),
            Map.entry("通信", new Profile(-0.003, 0.018)));

    /** 板块档案（漏登记的板块退化成横盘，绝不起不来：档案缺失只是不好看，不是崩）。 */
    public static Profile of(String sector) {
        return BY_SECTOR.getOrDefault(sector, FLAT);
    }

    /** 该板块是否显式登记过档案（StockDefsTest 用它确认 21 个板块一个都没漏）。 */
    public static boolean hasProfile(String sector) {
        return BY_SECTOR.containsKey(sector);
    }

    private PreHistoryDefs() {
    }
}
