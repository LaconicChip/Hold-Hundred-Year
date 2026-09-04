package com.holdhunderdyear.market.engine;

import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.enums.VolRegime;
import com.holdhunderdyear.market.quote.Quote;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三层因子波动引擎的统计性验证（P6 确定性：固定种子，全部断言覆盖 seed 1..5，不挑种子）。
 *
 * <p>五个统计断言：β 敏感性、板块联动、GARCH 聚集、日内 U 型、日收益肥尾。
 * 每跳标准调用序列：beginTick 一次 → 每股 rawReturn → 每股 updateGarch(realizedReturn)。
 * 阈值均按 seed 1..5 的实测效应量校准（见各断言注释），留有收敛后的稳定裕度。
 */
class VolatilityEngineTest {

    private static final long[] SEEDS = {1, 2, 3, 4, 5};

    /**
     * β 敏感性：同一 TickContext 序列下，β=1.5 与 β=0.7 的合成股票各 2000 跳
     * （NORMAL、muMkt=0、空 sectorCoefs），对重构的市场因子
     * rMkt_t = SIGMA_MKT·1.0·uShape(t)·zMkt_t 做原点回归，斜率各自 |slope−β| ≤ 0.35 且有序。
     * 实测：slope(β=1.5) ∈ [1.43, 1.54]，slope(β=0.7) ∈ [0.64, 0.76]。
     */
    @Test
    void betaSensitivityShowsInRegressionSlopeOnSharedMarketFactor() {
        StockDefs.StockDef hiBeta = new StockDefs.StockDef(991L, "T991", "测试高贝塔", "新能源", 100.0, 1.5, 0.0);
        StockDefs.StockDef loBeta = new StockDefs.StockDef(992L, "T992", "测试低贝塔", "新能源", 100.0, 0.7, 0.0);
        for (long seed : SEEDS) {
            VolatilityEngine engine = new VolatilityEngine();
            Random ctxRng = new Random(seed);
            Random hiRng = new Random(seed * 1_000 + 1);
            Random loRng = new Random(seed * 1_000 + 2);
            Quote hiQ = new Quote();
            Quote loQ = new Quote();
            int n = 2000;
            double[] rHi = new double[n];
            double[] rLo = new double[n];
            double[] rMkt = new double[n];
            for (int i = 0; i < n; i++) {
                VolatilityEngine.TickContext ctx = engine.beginTick(ctxRng);
                int tick = i % GameConstants.TOTAL_TICKS;
                rMkt[i] = GameConstants.SIGMA_MKT * VolatilityEngine.uShapeFactor(tick) * ctx.zMkt();
                rHi[i] = engine.rawReturn(hiQ, hiBeta, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, hiRng);
                rLo[i] = engine.rawReturn(loQ, loBeta, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, loRng);
                engine.updateGarch(hiQ, rHi[i]);
                engine.updateGarch(loQ, rLo[i]);
            }
            double slopeHi = originSlope(rHi, rMkt);
            double slopeLo = originSlope(rLo, rMkt);
            assertTrue(Math.abs(slopeHi - 1.5) <= 0.35,
                    "seed " + seed + ": slope(β=1.5)=" + slopeHi + " 偏离 1.5 超过 0.35");
            assertTrue(Math.abs(slopeLo - 0.7) <= 0.35,
                    "seed " + seed + ": slope(β=0.7)=" + slopeLo + " 偏离 0.7 超过 0.35");
            assertTrue(slopeHi > slopeLo,
                    "seed " + seed + ": slope(β=1.5)=" + slopeHi + " 必须大于 slope(β=0.7)=" + slopeLo);
        }
    }

    /**
     * 板块联动：同板块两只（卧龙新能/后羿光能）vs 跨板块两只（卧龙新能/大通银号），
     * Pearson 相关 corr(同板块) > corr(跨板块) + 0.05。
     * 计划原文为 2000 跳；提升到 6000 跳使 gap 收敛（2000 跳时种子间 gap 波动 0.05–0.15，
     * 个别种子贴近阈值）。实测 gap ∈ [0.074, 0.117]。
     */
    @Test
    void sameSectorPairOutCorrelatesCrossSectorPair() {
        StockDefs.StockDef wolong = StockDefs.byCode("600003").orElseThrow(); // 卧龙新能 新能源 β=1.15
        StockDefs.StockDef houyi = StockDefs.byCode("600004").orElseThrow();  // 后羿光能 新能源 β=1.10
        StockDefs.StockDef datong = StockDefs.byCode("600007").orElseThrow(); // 大通银号 银行 β=0.75
        for (long seed : SEEDS) {
            VolatilityEngine engine = new VolatilityEngine();
            Random ctxRng = new Random(seed);
            Random rngA = new Random(seed * 1_000 + 1);
            Random rngB = new Random(seed * 1_000 + 2);
            Random rngC = new Random(seed * 1_000 + 3);
            Quote qA = new Quote();
            Quote qB = new Quote();
            Quote qC = new Quote();
            int n = 6000;
            double[] rA = new double[n];
            double[] rB = new double[n];
            double[] rC = new double[n];
            for (int i = 0; i < n; i++) {
                VolatilityEngine.TickContext ctx = engine.beginTick(ctxRng);
                int tick = i % GameConstants.TOTAL_TICKS;
                rA[i] = engine.rawReturn(qA, wolong, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, rngA);
                rB[i] = engine.rawReturn(qB, houyi, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, rngB);
                rC[i] = engine.rawReturn(qC, datong, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, rngC);
                engine.updateGarch(qA, rA[i]);
                engine.updateGarch(qB, rB[i]);
                engine.updateGarch(qC, rC[i]);
            }
            double same = pearson(rA, rB);
            double cross = pearson(rA, rC);
            assertTrue(same > cross + 0.05,
                    "seed " + seed + ": corr(同板块)=" + same + " 必须大于 corr(跨板块)=" + cross + " + 0.05");
        }
    }

    /**
     * GARCH 聚集：每组全新 Quote 先跑 5 跳记录 |r|，注入一次 5σ 大跳
     * （updateGarch(q, 5·SIGMA_STOCK)），再跑 5 跳记录 |r|；聚合断言 avgAfter > avgBefore × 1.15。
     * 计划原文为 ≥40 组；用 1200 组使比值收敛到真实效应（实测 ratio ∈ [1.18, 1.22]，
     * 200 组时种子间波动 1.15–1.27，个别种子贴近阈值）。
     */
    @Test
    void garchClustersVolatilityAfterFiveSigmaJump() {
        StockDefs.StockDef def = new StockDefs.StockDef(993L, "T993", "测试丙", "新能源", 100.0, 1.0, 0.0);
        for (long seed : SEEDS) {
            VolatilityEngine engine = new VolatilityEngine();
            Random ctxRng = new Random(seed);
            Random rng = new Random(seed * 1_000 + 1);
            int trials = 1200;
            double sumBefore = 0.0;
            double sumAfter = 0.0;
            for (int t = 0; t < trials; t++) {
                Quote q = new Quote();
                for (int tick = 40; tick < 45; tick++) {
                    VolatilityEngine.TickContext ctx = engine.beginTick(ctxRng);
                    double r = engine.rawReturn(q, def, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, rng);
                    sumBefore += Math.abs(r);
                    engine.updateGarch(q, r);
                }
                engine.updateGarch(q, 5.0 * GameConstants.SIGMA_STOCK); // 注入一次 5σ 大跳
                for (int tick = 45; tick < 50; tick++) {
                    VolatilityEngine.TickContext ctx = engine.beginTick(ctxRng);
                    double r = engine.rawReturn(q, def, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, rng);
                    sumAfter += Math.abs(r);
                    engine.updateGarch(q, r);
                }
            }
            double avgBefore = sumBefore / (trials * 5);
            double avgAfter = sumAfter / (trials * 5);
            assertTrue(avgAfter > avgBefore * 1.15,
                    "seed " + seed + ": avgAfter=" + avgAfter + " 必须大于 avgBefore=" + avgBefore
                            + " × 1.15（实测比 " + (avgAfter / avgBefore) + "）");
        }
    }

    /**
     * U 型：完整交易日（每跳 NORMAL、muMkt=0，garchK 跨日延续），tick 0..10 与 80..89 的
     * 平均 |r| 均 > tick 30..60 平均 |r| × 1.5。计划原文为 ≥22 日；用 1000 日稳定分桶均值
     * （实测 open/mid ≈ 2.2、close/mid ≈ 1.86；短窗口下日内 GARCH 自相关会放大波动）。
     */
    @Test
    void intradayAbsoluteReturnsFollowUShape() {
        StockDefs.StockDef def = new StockDefs.StockDef(994L, "T994", "测试丁", "新能源", 100.0, 1.0, 0.0);
        for (long seed : SEEDS) {
            VolatilityEngine engine = new VolatilityEngine();
            Random ctxRng = new Random(seed);
            Random rng = new Random(seed * 1_000 + 1);
            Quote q = new Quote();
            int days = 1000;
            double sumOpen = 0.0;
            double sumMid = 0.0;
            double sumClose = 0.0;
            int nOpen = 0;
            int nMid = 0;
            int nClose = 0;
            for (int day = 0; day < days; day++) {
                for (int tick = 0; tick < GameConstants.TOTAL_TICKS; tick++) {
                    VolatilityEngine.TickContext ctx = engine.beginTick(ctxRng);
                    double r = engine.rawReturn(q, def, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, rng);
                    engine.updateGarch(q, r);
                    double abs = Math.abs(r);
                    if (tick <= 10) {
                        sumOpen += abs;
                        nOpen++;
                    } else if (tick >= 30 && tick <= 60) {
                        sumMid += abs;
                        nMid++;
                    } else if (tick >= 80) {
                        sumClose += abs;
                        nClose++;
                    }
                }
            }
            double avgOpen = sumOpen / nOpen;
            double avgMid = sumMid / nMid;
            double avgClose = sumClose / nClose;
            assertTrue(avgOpen > avgMid * 1.5,
                    "seed " + seed + ": 开盘段 |r|=" + avgOpen + " 必须大于盘中段 " + avgMid + " × 1.5（实测比 "
                            + (avgOpen / avgMid) + "）");
            assertTrue(avgClose > avgMid * 1.5,
                    "seed " + seed + ": 尾盘段 |r|=" + avgClose + " 必须大于盘中段 " + avgMid + " × 1.5（实测比 "
                            + (avgClose / avgMid) + "）");
        }
    }

    /**
     * 肥尾：完整交易日的日收益（90 跳求和），统计经验 σ；断言 |日收益| > 3σ_emp 的经验频率
     * > 同方差正态预测值 2·(1−Φ(3)) × 1.5。
     *
     * <p>阈值取 3σ_emp 而非计划原文的固定 5%：实测 σ_emp ≈ 4.8%，5% 仅为 ≈1.05σ，
     * 混合分布（GARCH × 跳跃）在中心区频率反而低于正态（实测比 0.90–0.98），
     * 肥尾信号自 ≥2.5σ 起显现。样本量取 20000 日/种子以保证 3σ 计数稳定
     * （实测频率 0.48%–0.59% vs 阈值 0.405%，计数 95–117 对阈值 80）。
     */
    @Test
    void dailyReturnsAreFatTailedRelativeToGaussian() {
        StockDefs.StockDef def = new StockDefs.StockDef(995L, "T995", "测试戊", "新能源", 100.0, 1.0, 0.0);
        for (long seed : SEEDS) {
            VolatilityEngine engine = new VolatilityEngine();
            Random ctxRng = new Random(seed);
            Random rng = new Random(seed * 1_000 + 1);
            Quote q = new Quote();
            int days = 20000;
            double[] daily = new double[days];
            for (int day = 0; day < days; day++) {
                double daySum = 0.0;
                for (int tick = 0; tick < GameConstants.TOTAL_TICKS; tick++) {
                    VolatilityEngine.TickContext ctx = engine.beginTick(ctxRng);
                    double r = engine.rawReturn(q, def, 0.0, Map.of(), VolRegime.NORMAL, tick, ctx, rng);
                    engine.updateGarch(q, r);
                    daySum += r;
                }
                daily[day] = daySum;
            }
            double sigma = std(daily);
            double normalPred = 2.0 * normalTail(3.0);
            int exceed = 0;
            for (double d : daily) {
                if (Math.abs(d) > 3.0 * sigma) {
                    exceed++;
                }
            }
            double freq = (double) exceed / days;
            assertTrue(freq > normalPred * 1.5,
                    "seed " + seed + ": σ_emp=" + sigma + ", |r|>3σ 经验频率=" + freq + " 必须大于正态预测 "
                            + normalPred + " × 1.5（实测比 " + (freq / normalPred) + "）");
        }
    }

    /** 原点回归斜率 Σxy / Σx²。 */
    private static double originSlope(double[] y, double[] x) {
        double sxy = 0.0;
        double sxx = 0.0;
        for (int i = 0; i < x.length; i++) {
            sxy += x[i] * y[i];
            sxx += x[i] * x[i];
        }
        return sxy / sxx;
    }

    /** Pearson 相关系数。 */
    private static double pearson(double[] x, double[] y) {
        int n = x.length;
        double mx = 0.0;
        double my = 0.0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0.0;
        double sxx = 0.0;
        double syy = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    /** 样本标准差（n−1 分母）。 */
    private static double std(double[] v) {
        int n = v.length;
        double m = 0.0;
        for (double x : v) {
            m += x;
        }
        m /= n;
        double s = 0.0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (n - 1));
    }

    /** Abramowitz-Stegun 7.1.26 erf 近似（最大绝对误差 1.5e-7）。 */
    private static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly = t * (0.254829592
                + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
        double y = 1.0 - poly * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    /** 标准正态上尾 1−Φ(z)（z ≥ 0）。 */
    private static double normalTail(double z) {
        return 0.5 * (1.0 - erf(z / Math.sqrt(2.0)));
    }
}
