package com.holdhundredyear.market.engine;

import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.constants.StockDefs.StockDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 百年指数计算器验证（spec §4.2：等权合成、基期 1000）。
 * 指数按未停牌股票的加权平均收益推进：index *= (1 + Σw·r / Σw)，
 * 权重和归一化，停牌股由调用方剔除后剩余权重重归一；全部断言精确到 1e-9。
 */
class IndexCalculatorTest {

    private static final double BASE_INDEX = 1000.0;
    private static final double EPS = 1e-9;

    private final IndexCalculator calculator = new IndexCalculator();

    /** 全部 24 只同收益的收益表。 */
    private Map<Long, Double> uniformReturns(double r) {
        Map<Long, Double> returns = new HashMap<>();
        for (StockDef def : StockDefs.ALL) {
            returns.put(def.id(), r);
        }
        return returns;
    }

    @Test
    void allUpOnePercentMovesIndexFrom1000To1010() {
        assertEquals(1010.0, calculator.nextIndex(BASE_INDEX, StockDefs.ALL, uniformReturns(0.01)), EPS,
                "24 只全部涨 1%：指数 1000 → 1010");
    }

    @Test
    void singleWeightedMoverContributesWeightTimesReturn() {
        Map<Long, Double> returns = uniformReturns(0.0);
        returns.put(1L, 0.10);
        double expected = 1000.0 * (1.0 + 0.10 * (1.0 / 24.0));
        assertEquals(expected, calculator.nextIndex(BASE_INDEX, StockDefs.ALL, returns), EPS,
                "权重股影响 = weight×10% = 10%/24");
    }

    @Test
    void suspendedStockRemovedWeightsRenormalize() {
        List<StockDef> active = new ArrayList<>(StockDefs.ALL);
        active.removeIf(def -> def.id() == 1L); // 模拟 1 只停牌被剔除，剩 23 只
        Map<Long, Double> returns = new HashMap<>();
        for (StockDef def : active) {
            returns.put(def.id(), 0.0);
        }
        returns.put(2L, 0.10);
        double expected = 1000.0 * (1.0 + 0.10 / 23.0);
        assertEquals(expected, calculator.nextIndex(BASE_INDEX, active, returns), EPS,
                "停牌剔除重归一：单只 +10% 影响 = 10%/23（Σw·r/Σw = (0.10/24)/(23/24)）");
    }

    @Test
    void emptyCollectionKeepsIndexUnchanged() {
        assertEquals(1234.5, calculator.nextIndex(1234.5, List.of(), Map.of()), EPS,
                "空集合（全部停牌）返回 prevIndex 不变");
    }

    @Test
    void mixedReturnsMatchHandComputedWeightedAverage() {
        // 手算：自定义权重 2/1/1（Σw=4），收益 +8%/-4%/缺报（按 0 处理）
        // Σw·r = 2×0.08 + 1×(-0.04) + 1×0 = 0.12；归一 0.12/4 = 0.03 → 1000×1.03 = 1030
        List<StockDef> stocks = List.of(
                new StockDef(101L, "900001", "甲", "白酒", 10.0, 1.0, 1.0, 0, 1.0, 2.0),
                new StockDef(102L, "900002", "乙", "医药", 10.0, 1.0, 1.0, 0, 1.0, 1.0),
                new StockDef(103L, "900003", "丙", "军工", 10.0, 1.0, 1.0, 0, 1.0, 1.0));
        Map<Long, Double> returns = new HashMap<>();
        returns.put(101L, 0.08);
        returns.put(102L, -0.04);

        assertEquals(1030.0, calculator.nextIndex(BASE_INDEX, stocks, returns), EPS,
                "混合收益按 Σw·r/Σw 加权平均，returns 缺报的股票按 0 处理");
    }

    @Test
    void zeroWeightSumKeepsIndexUnchanged() {
        List<StockDef> stocks = List.of(
                new StockDef(201L, "900011", "丁", "白酒", 10.0, 1.0, 1.0, 0, 1.0, 0.0));
        assertEquals(1000.0, calculator.nextIndex(BASE_INDEX, stocks, Map.of(201L, 0.05)), EPS,
                "权重和为 0（除零保护）返回 prevIndex 不变");
    }
}
