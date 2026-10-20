package com.holdhundredyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "个股齐动度"诊断与回归闸门（2026-10-20）。
 *
 * <p>玩家反馈：每只股票的波动节奏很像，都会在固定时间突然大涨或大跌一轮。规格里"齐涨齐跌"
 * 只该出现在<b>市场因子</b>那一层（大盘与板块的共同节奏），个股之间不该有共性。本测试把这条
 * 主观反馈变成可测的数字：
 *
 * <ol>
 *   <li><b>日内轨迹两两相关</b>：两只票各自 90 跳收益序列的皮尔逊相关，跨全部票对取平均。
 *       纯独立游走应当趋近 0；市场因子天然带来 0.2 上下；超过 0.5 就是"大家一起动"。</li>
 *   <li><b>去掉市场因子后的残差相关</b>：把每跳的 24 只票平均收益（市场代理）从各自序列里
 *       减掉再算相关。残差仍高＝个股之间还有共性（同步的 U 型、同步的剧本冲击）。</li>
 *   <li><b>爆发起点分布</b>：每股每天最大的一段 10 跳累计变动发生在哪一跳。全部挤在同样的
 *       时段＝玩家看到的"固定时间集体暴动"。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class VolatilityCoMovementTest {

    /** 跑几个交易日：跨日才有"这只票是不是天天同一个时间暴动"的样本。 */
    private static final int DAYS = 6;

    @Autowired private WorldSeeder worldSeeder;
    @Autowired private MarketStateService marketStateService;
    @Autowired private WorldTickService worldTickService;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private UserGameStateMapper userGameStateMapper;

    @Test
    void stocksDoNotMoveInLockstep() {
        long userId = worldSeeder.seedUser("comove_probe", "$2a$10$bcrypthash");
        setWorldSeed(userId, 20261020L);

        QuoteBoard probe = quoteBoardRegistry.getOrLoad(userId);
        List<Long> ids = new ArrayList<>();
        for (Quote q : probe.getQuotes().values()) {
            ids.add(q.getStockId());
        }
        ids.sort(Long::compareTo);

        // data[day][stockIdx][tick] = 该跳的简单收益率
        List<double[][]> days = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            marketStateService.startDay(userId);
            QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
            double[][] perStock = new double[ids.size()][90];
            double[] prev = new double[ids.size()];
            for (int i = 0; i < 45; i++) {
                capturePrev(board, ids, prev);
                worldTickService.tickOne(userId);
                recordReturn(board, ids, prev, perStock, i);
            }
            marketStateService.resumeAfternoon(userId);
            for (int i = 0; i < 45; i++) {
                capturePrev(board, ids, prev);
                worldTickService.tickOne(userId);
                recordReturn(board, ids, prev, perStock, 45 + i);
            }
            days.add(perStock);
        }

        // —— ① 原始序列的两两相关 ——
        double rawCorrSum = 0;
        int rawN = 0;
        // —— ② 去掉市场代理后的残差两两相关 ——
        double resCorrSum = 0;
        int resN = 0;
        // —— ③ 爆发起点（残差口径） ——
        int[] segHist = new int[9];
        int burstN = 0;
        // —— ④ 每跳的平均绝对收益（看日内形状：U 型在不在、是不是同步的） ——
        double[] tickAbs = new double[90];
        double[] tickSigned = new double[90];

        for (double[][] perStock : days) {
            int n = perStock.length;
            double[] market = new double[90];
            for (int s = 0; s < n; s++) {
                for (int t = 0; t < 90; t++) {
                    market[t] += perStock[s][t] / n;
                }
            }
            double[][] resid = new double[n][90];
            for (int s = 0; s < n; s++) {
                for (int t = 0; t < 90; t++) {
                    resid[s][t] = perStock[s][t] - market[t];
                    tickAbs[t] += Math.abs(perStock[s][t]) / (n * (double) days.size());
                    tickSigned[t] += perStock[s][t] / (n * (double) days.size());
                }
            }
            for (int a = 0; a < n; a++) {
                for (int b = a + 1; b < n; b++) {
                    double c1 = pearson(perStock[a], perStock[b]);
                    double c2 = pearson(resid[a], resid[b]);
                    if (!Double.isNaN(c1)) {
                        rawCorrSum += c1;
                        rawN++;
                    }
                    if (!Double.isNaN(c2)) {
                        resCorrSum += c2;
                        resN++;
                    }
                }
            }
            for (int s = 0; s < n; s++) {
                int best = 0;
                double bestAbs = -1;
                for (int start = 0; start + 10 <= 90; start++) {
                    double cum = 0;
                    for (int k = start; k < start + 10; k++) {
                        cum += resid[s][k];
                    }
                    if (Math.abs(cum) > bestAbs) {
                        bestAbs = Math.abs(cum);
                        best = start;
                    }
                }
                segHist[best / 10]++;
                burstN++;
            }
        }
        double rawCorr = rawN == 0 ? 0 : rawCorrSum / rawN;
        double resCorr = resN == 0 ? 0 : resCorrSum / resN;
        int maxSeg = 0;
        for (int v : segHist) {
            maxSeg = Math.max(maxSeg, v);
        }
        double maxSegShare = (double) maxSeg / burstN;

        System.out.println(String.format(
                "COMOVE rawCorr=%.3f residCorr=%.3f maxBurstSegShare=%.2f pct",
                rawCorr, resCorr, maxSegShare * 100));
        System.out.println("COMOVE burstSegHist=" + Arrays.toString(segHist));
        System.out.println("COMOVE tickAbsMean=" + Arrays.toString(round(tickAbs, 100000)));
        System.out.println("COMOVE tickSignedMean=" + Arrays.toString(round(tickSigned, 100000)));

        assertTrue(rawCorr < 0.5,
                "intraday paths must not be near-identical across stocks: average pairwise correlation is " + rawCorr);
        assertTrue(resCorr < 0.3,
                "after removing the market factor, stocks must still be independent: residual correlation is " + resCorr);
        assertTrue(maxSegShare < 0.35,
                "big moves must not all land in the same window of the day: worst 10-tick band holds "
                        + (maxSegShare * 100) + " pct of them");
    }

    /** 记录一跳的收益：先存旧价，tick 之后用新价算简单收益率。 */
    private void capturePrev(QuoteBoard board, List<Long> ids, double[] prev) {
        for (int s = 0; s < ids.size(); s++) {
            prev[s] = board.quote(ids.get(s)).getCurrent();
        }
    }

    private void recordReturn(QuoteBoard board, List<Long> ids, double[] prev, double[][] perStock, int tick) {
        for (int s = 0; s < ids.size(); s++) {
            double now = board.quote(ids.get(s)).getCurrent();
            perStock[s][tick] = prev[s] > 0 ? (now - prev[s]) / prev[s] : 0;
        }
    }

    private static double[] round(double[] xs, int scale) {
        double[] out = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = Math.round(xs[i] * scale) / (double) scale;
        }
        return out;
    }

    private static double pearson(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < n; i++) {
            ma += a[i];
            mb += b[i];
        }
        ma /= n;
        mb /= n;
        double cov = 0;
        double va = 0;
        double vb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma;
            double db = b[i] - mb;
            cov += da * db;
            va += da * da;
            vb += db * db;
        }
        if (va <= 0 || vb <= 0) {
            return Double.NaN;
        }
        return cov / Math.sqrt(va * vb);
    }

    private void setWorldSeed(long userId, long seed) {
        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setWorldSeed(seed);
        userGameStateMapper.updateById(gs);
    }
}
