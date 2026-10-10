package com.holdhundredyear.market.engine;

import com.holdhundredyear.game.constants.StockDefs.StockDef;

import java.util.Collection;
import java.util.Map;

/**
 * 百年指数计算器（spec §4.2：等权合成、基期 1000、按未停牌股票的平均涨跌幅更新）。
 * 纯计算、无状态、不依赖 Spring/DB。
 */
public class IndexCalculator {

    /**
     * 每跳调用：对给定（未停牌）股票集合按 index_weight 加权平均收益推进指数。
     * index *= (1 + Σ w_i·r_i / Σ w_i)——权重和归一化，停牌股由调用方剔除。
     * 空集合或权重和为 0 → 返回 prevIndex 不变。
     */
    public double nextIndex(double prevIndex, Collection<StockDef> activeStocks, Map<Long, Double> returns) {
        double weightedReturnSum = 0.0;
        double weightSum = 0.0;
        for (StockDef def : activeStocks) {
            weightedReturnSum += def.weight() * returns.getOrDefault(def.id(), 0.0);
            weightSum += def.weight();
        }
        if (weightSum == 0.0) {
            return prevIndex;
        }
        return prevIndex * (1.0 + weightedReturnSum / weightSum);
    }
}
