package com.holdhunderdyear.game.constants;

import com.holdhunderdyear.game.model.enums.EraCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 11 个纪元的基准时间表与强度区间（spec §3/§4，108 游戏日）。
 * μ 为每跳市场漂移；sectorRanges 为板块相对市场的额外系数区间。
 * sectorRanges 使用 LinkedHashMap，保证 EraScheduler 消费随机数的顺序确定。
 */
public final class EraDefs {

    public record SectorRange(double min, double max) {
    }

    public record EraDef(int eraIndex, EraCode code, int baseStartDay, int baseEndDay,
                         double muMin, double muMax, Map<String, SectorRange> sectorRanges) {
    }

    public static final List<EraDef> ALL = List.of(
            new EraDef(1, EraCode.BULL_START, 1, 10, 0.0002, 0.0004, ranges(
                    entry("券商", 0.0008, 0.0018),
                    entry("银行", 0.0003, 0.0008),
                    entry("保险", 0.0003, 0.0008))),
            new EraDef(2, EraCode.EUPHORIA, 11, 20, 0.0008, 0.0012, ranges(
                    entry("传媒", 0.0010, 0.0022),
                    entry("算力", 0.0010, 0.0022),
                    entry("通信", 0.0008, 0.0018),
                    entry("半导体", 0.0008, 0.0018),
                    entry("白酒", -0.0003, -0.0001),
                    entry("银行", -0.0003, -0.0001),
                    entry("保险", -0.0003, -0.0001),
                    entry("煤炭", -0.0003, -0.0001),
                    entry("钢铁", -0.0003, -0.0001),
                    entry("农业", -0.0003, -0.0001),
                    entry("零售", -0.0003, -0.0001))),
            new EraDef(3, EraCode.CRASH, 21, 28, -0.0014, -0.0010, ranges(
                    entry("传媒", -0.0025, -0.0015),
                    entry("算力", -0.0025, -0.0015),
                    entry("通信", -0.0020, -0.0010),
                    entry("半导体", -0.0020, -0.0010))),
            new EraDef(4, EraCode.POST_CRASH, 29, 36, -0.0001, 0.0001, ranges(
                    entry("券商", 0.0005, 0.0010),
                    entry("传媒", -0.0006, -0.0002),
                    entry("算力", -0.0004, -0.0001))),
            new EraDef(5, EraCode.CIRCUIT_BREAKER, 37, 40, -0.0022, -0.0018, ranges()),
            new EraDef(6, EraCode.BLUE_CHIP, 41, 56, 0.0002, 0.0004, ranges(
                    entry("白酒", 0.0006, 0.0014),
                    entry("家电", 0.0006, 0.0014),
                    entry("医药", 0.0006, 0.0014),
                    entry("银行", 0.0003, 0.0008),
                    entry("保险", 0.0003, 0.0008),
                    entry("传媒", -0.0006, -0.0002))),
            new EraDef(7, EraCode.TRADE_WAR, 57, 72, -0.0005, -0.0003, ranges(
                    entry("农业", 0.0005, 0.0012),
                    entry("半导体", -0.0012, -0.0006),
                    entry("通信", -0.0012, -0.0006),
                    entry("军工", -0.0008, -0.0003),
                    entry("制造", -0.0008, -0.0003))),
            new EraDef(8, EraCode.NEW_ENERGY, 73, 84, 0.0002, 0.0004, ranges(
                    entry("新能源", 0.0010, 0.0022),
                    entry("物流", 0.0005, 0.0012),
                    entry("地产", -0.0006, -0.0001),
                    entry("银行", -0.0004, -0.0001))),
            new EraDef(9, EraCode.AI_BULL, 85, 96, 0.0008, 0.0012, ranges(
                    entry("算力", 0.0015, 0.0030),
                    entry("半导体", 0.0010, 0.0020),
                    entry("通信", 0.0010, 0.0020),
                    entry("传媒", 0.0008, 0.0015),
                    entry("白酒", -0.0008, -0.0002),
                    entry("银行", -0.0006, -0.0001),
                    entry("煤炭", -0.0006, -0.0001),
                    entry("钢铁", -0.0006, -0.0001))),
            new EraDef(10, EraCode.BLACK_JULY, 97, 104, -0.0014, -0.0010, ranges(
                    entry("算力", -0.0028, -0.0018),
                    entry("半导体", -0.0028, -0.0018),
                    entry("通信", -0.0022, -0.0012),
                    entry("银行", 0.0008, 0.0016),
                    entry("煤炭", 0.0008, 0.0016),
                    entry("白酒", 0.0005, 0.0012))),
            new EraDef(11, EraCode.REBALANCE, 105, 108, -0.0001, 0.0001, ranges(
                    entry("算力", 0.0004, 0.0010),
                    entry("半导体", 0.0004, 0.0010),
                    entry("通信", 0.0003, 0.0008),
                    entry("传媒", -0.0004, -0.0001))));

    private EraDefs() {
    }

    /** 按声明顺序构建 LinkedHashMap，固定掷强度时的随机数消费顺序。 */
    @SafeVarargs
    private static Map<String, SectorRange> ranges(Map.Entry<String, SectorRange>... entries) {
        Map<String, SectorRange> map = new LinkedHashMap<>();
        for (Map.Entry<String, SectorRange> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map.Entry<String, SectorRange> entry(String sector, double min, double max) {
        return Map.entry(sector, new SectorRange(min, max));
    }
}
