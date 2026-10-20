package com.holdhundredyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.game.model.dto.IntradayPointDTO;
import com.holdhundredyear.game.model.dto.KlinePointDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.entity.StockDailyBar;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.mapper.StockDailyBarMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整局波动的分布断言（spec §5.2 标定验收）：真的用快进跑完整局 72 个交易日，从
 * stock_daily_bar 统计"日收益标准差 / 大波动日占比 / 贴板日占比 / 隔夜跳空 / 日内振幅"，
 * 并把三个种子的日K与分时序列导成 CSV 给标定预览图用。
 *
 * <p>为什么要这个测试：每跳 σ、跳跃概率、GARCH 上限这些常量在纸面上互相纠缠，再叠加开盘
 * 跳空、磁吸封板与剧本钉板，只有跑完整局才看得见玩家实际遇到什么。2026-10-16 的重标定
 * 就是靠它把"不是涨停就是跌停"压回券商行情的量级。
 *
 * <p>阈值口径：贴板只算到 ±10% 的（ST 的 ±5% 与停牌日天然落在外面）；剧本日（千股涨停/
 * 跌停、妖股连板）也算进来——玩家不看成因，他只看到满屏涨停。
 *
 * <p><b>2026-10-20：世界种子钉死</b>。原来用 seedUser 随机掷种子，导致三轮之间的
 * 有机贴板率能差 3 个百分点（10.9%~17.1%），标定时根本分不清"改了参数"还是"换了一局"。
 * 现在固定三个种子，跨轮次可逐项对比。
 */
@SpringBootTest
@Transactional
class VolatilityDistributionTest {

    /** 跑几局：分布要跨局平均，单局会被某一天的剧本带偏。 */
    private static final int RUNS = 3;
    private static final int SAMPLE_DAY = 30;
    private static final long[] SAMPLE_STOCKS = {1L, 5L, 12L};
    private static final int DAYS = GameMode.STANDARD.totalDays();
    /** 固定的世界种子（2026-10-20 引擎大改标定用）：跨轮次可复现、可对比。 */
    private static final long[] WORLD_SEEDS = {20261020L, 20261021L, 20261022L};

    @Autowired private WorldSeeder worldSeeder;
    @Autowired private FastForwardService fastForwardService;
    @Autowired private MarketQueryService marketQueryService;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private StockDailyBarMapper stockDailyBarMapper;
    @Autowired private UserScriptDayMapper userScriptDayMapper;

    @Test
    void wholeGameDistributionSitsInsideTheBrokerBand() throws IOException {
        double pinnedSum = 0;
        double bigSum = 0;
        double sigmaSum = 0;
        double organicSigmaSum = 0;
        double organicPinnedSum = 0;
        double organicBigSum = 0;
        for (int run = 0; run < RUNS; run++) {
            long userId = worldSeeder.seedUser("vd_" + run, "$2a$10$bcrypthash");
            // 固定世界种子：排期与价格随机流都来自它，跨轮次才可比
            setWorldSeed(userId, WORLD_SEEDS[run]);
            long seed = worldSeed(userId);
            for (int day = 1; day <= DAYS; day++) {
                fastForwardService.skip(userId);
                if (day == SAMPLE_DAY) {
                    dumpIntraday(seed, userId);
                }
            }
            Stats stats = measure(userId, scriptedDays(userId));
            dumpKline(seed, userId);
            pinnedSum += stats.pinnedShare;
            bigSum += stats.bigShare;
            sigmaSum += stats.sigma;
            System.out.println(String.format(
                    "VOLPROBE seed=%d bars=%d sigma=%.3f pct meanAbs=%.3f pct p99=%.3f pct pinned=%.2f pct big6=%.2f pct gapSd=%.3f pct range=%.2f pct",
                    seed, stats.count, stats.sigma * 100, stats.meanAbs * 100, stats.p99Abs * 100,
                    stats.pinnedShare * 100, stats.bigShare * 100, stats.gapSigma * 100, stats.avgRange * 100));
            System.out.println(String.format(
                    "VOLPROBE-ORGANIC bars=%d sigma=%.3f pct pinned=%.2f pct big6=%.2f pct",
                    stats.organicCount, stats.organicSigma * 100, stats.organicPinnedShare * 100,
                    stats.organicBigShare * 100));
            // 个股离散度：死水票与疯票的日 σ 必须拉开（玩家反馈"每只股都很相似"的另一半）
            System.out.println("VOLPROBE-PERSTOCK " + perStockSigma(userId, scriptedDays(userId)));
            organicSigmaSum += stats.organicSigma;
            organicPinnedSum += stats.organicPinnedShare;
            organicBigSum += stats.organicBigShare;
            assertTrue(stats.count > 1500, "a full run must settle every stock every day, got " + stats.count);
        }
        double runs = RUNS;
        double pinned = pinnedSum / runs;
        double big = bigSum / runs;
        double sigma = sigmaSum / runs;
        double organicSigma = organicSigmaSum / runs;
        double organicPinned = organicPinnedSum / runs;
        double organicBig = organicBigSum / runs;
        System.out.println(String.format(
                "VOLPROBE-AVG all sigma=%.3f big6=%.2f pinned=%.2f | ORGANIC sigma=%.3f big6=%.2f pinned=%.2f",
                sigma * 100, big * 100, pinned * 100, organicSigma * 100, organicBig * 100, organicPinned * 100));

        // —— 以下三条只量"有机行情"，剧本日不参与 ——
        // 2026-10-20 引擎大改：用户明确要"够刺激"档——日 σ 4.5%~5.5%。
        // 真实 A 股个股日 σ 约 2%，本游戏 24 只票清一色板块龙头/题材股，天然偏高，
        // 所以上限给到 5.5%（重标定前是 8.0%，等于每天一半时间在板上）。
        assertTrue(organicSigma > 0.042 && organicSigma < 0.058,
                "organic daily sigma must sit in the 4.2~5.8 pct band, got " + (organicSigma * 100) + " pct");
        // 24 只票的世界里，有机贴板 12% 约等于"每天 2.9 只票上板"——那是可以追涨停的玩法；
        // 重标定前是 55%（抛硬币决定涨停还是跌停），那才是玩家报的问题。
        // （2026-10-20 引擎大改后 σ 抬到 5% 上下，贴板率从 55% 降到 11%~12%，
        //  但绝对量仍比旧版"1.4 只/天"高——用户 2026-10-20 明确选了"够刺激"档。）
        assertTrue(organicPinned < 0.12,
                "organic pinned days must stay under 12 pct, got " + (organicPinned * 100) + " pct");
        assertTrue(organicBig < 0.26,
                "organic days beyond 6 pct must stay under 26 pct, got " + (organicBig * 100) + " pct");
        // 剧本日照旧要轰轰烈烈：这里只保证名场面确实落地了
        assertTrue(pinned > 0.03,
                "scripted set pieces must still land, overall pinned share was " + (pinned * 100) + " pct");
    }

    /** 每股的有机日 σ（打出来看离散度）：死水票与疯票必须拉开。 */
    private String perStockSigma(long userId, Set<Integer> scripted) {
        List<StockDailyBar> bars = stockDailyBarMapper.selectList(
                new LambdaQueryWrapper<StockDailyBar>()
                        .eq(StockDailyBar::getUserId, userId)
                        .ge(StockDailyBar::getGameDay, 1));
        Map<Long, List<Double>> byStock = new LinkedHashMap<>();
        for (StockDailyBar bar : bars) {
            if (scripted.contains(bar.getGameDay())) {
                continue;
            }
            byStock.computeIfAbsent(bar.getStockId(), k -> new ArrayList<>())
                    .add(bar.getChangePct() == null ? 0 : bar.getChangePct());
        }
        StringBuilder sb = new StringBuilder();
        byStock.forEach((id, rets) -> sb.append(id).append(":")
                .append(String.format("%.2f", std(rets) * 100)).append(" "));
        return sb.toString();
    }

    /** 本局被剧本点名的日子（千股涨停/跌停、熔断、妖股连板、复牌潮、打新上市…）。 */
    private Set<Integer> scriptedDays(long userId) {
        Set<Integer> days = new HashSet<>();
        for (UserScriptDay row : userScriptDayMapper.selectList(
                new LambdaQueryWrapper<UserScriptDay>().eq(UserScriptDay::getUserId, userId))) {
            days.add(row.getGameDay());
        }
        return days;
    }

    /**
     * 一局跑完后的分布统计（只算本局 game_day >= 1，开局前历史 K 不参与）。
     *
     * <p><b>有机行情与剧本日必须分开量。</b>千股涨停那几天一只票都不例外，混进平均值会得到
     * "贴板日 15%"这种数字——那是名场面，不是 bug。玩家抱怨的是"就算是平稳行情也不是涨停
     * 就是跌停"，所以验收看 organic 这一组。
     */
    private Stats measure(long userId, Set<Integer> scripted) {
        List<StockDailyBar> bars = stockDailyBarMapper.selectList(
                new LambdaQueryWrapper<StockDailyBar>()
                        .eq(StockDailyBar::getUserId, userId)
                        .ge(StockDailyBar::getGameDay, 1));
        bars.sort(Comparator.comparingLong(StockDailyBar::getStockId)
                .thenComparingInt(StockDailyBar::getGameDay));
        double limit = GameMode.STANDARD.getLimitPct();
        List<Double> rets = new ArrayList<>();
        List<Double> gaps = new ArrayList<>();
        List<Double> organicRets = new ArrayList<>();
        int organicPinned = 0;
        int organicBig = 0;
        double rangeSum = 0;
        int pinned = 0;
        int big = 0;
        for (int i = 0; i < bars.size(); i++) {
            StockDailyBar bar = bars.get(i);
            double ret = bar.getChangePct() == null ? 0 : bar.getChangePct();
            rets.add(ret);
            double close = bar.getClose().doubleValue();
            rangeSum += close > 0 ? (bar.getHigh().doubleValue() - bar.getLow().doubleValue()) / close : 0;
            boolean organic = !scripted.contains(bar.getGameDay());
            if (organic) {
                organicRets.add(ret);
                if (Math.abs(ret) >= limit * 0.995) {
                    organicPinned++;
                }
                if (Math.abs(ret) >= 0.06) {
                    organicBig++;
                }
            }
            if (Math.abs(ret) >= limit * 0.995) {
                pinned++;
            }
            if (Math.abs(ret) >= 0.06) {
                big++;
            }
            if (i > 0 && bars.get(i - 1).getStockId() == bar.getStockId()) {
                double prevClose = bars.get(i - 1).getClose().doubleValue();
                if (prevClose > 0) {
                    gaps.add(bar.getOpen().doubleValue() / prevClose - 1);
                }
            }
        }
        Stats s = new Stats();
        s.count = bars.size();
        s.sigma = std(rets);
        s.meanAbs = meanAbs(rets);
        s.p99Abs = percentileAbs(rets);
        s.pinnedShare = bars.isEmpty() ? 0 : (double) pinned / bars.size();
        s.bigShare = bars.isEmpty() ? 0 : (double) big / bars.size();
        s.gapSigma = std(gaps);
        s.avgRange = bars.isEmpty() ? 0 : rangeSum / bars.size();
        s.organicCount = organicRets.size();
        s.organicSigma = std(organicRets);
        s.organicPinnedShare = organicRets.isEmpty() ? 0 : (double) organicPinned / organicRets.size();
        s.organicBigShare = organicRets.isEmpty() ? 0 : (double) organicBig / organicRets.size();
        return s;
    }

    private static final class Stats {
        private int count;
        private double sigma;
        private double meanAbs;
        private double p99Abs;
        private double pinnedShare;
        private double bigShare;
        private double gapSigma;
        private double avgRange;
        private int organicCount;
        private double organicSigma;
        private double organicPinnedShare;
        private double organicBigShare;
    }

    /** 日K：直接导**前复权**序列，和玩家屏幕上看到的一模一样（target/ 已 gitignore）。 */
    private void dumpKline(long seed, long userId) throws IOException {
        for (long stockId : SAMPLE_STOCKS) {
            List<KlinePointDTO> kline = marketQueryService.stockKline(userId, stockId, 500);
            StringBuilder sb = new StringBuilder("day,open,high,low,close,pct\n");
            for (KlinePointDTO p : kline) {
                sb.append(p.getGameDay()).append(",");
                sb.append(p.getOpen()).append(",");
                sb.append(p.getHigh()).append(",");
                sb.append(p.getLow()).append(",");
                sb.append(p.getClose()).append(",");
                sb.append(p.getChangePct()).append("\n");
            }
            Files.writeString(Path.of("target", "vol-kline-" + seed + "-" + stockId + ".csv"), sb.toString());
        }
    }

    /** 分时：采样日收盘后读当日 90 点时间轴（与 GET /api/stocks/{id}/quotes 同一条路）。 */
    private void dumpIntraday(long seed, long userId) throws IOException {
        for (long stockId : SAMPLE_STOCKS) {
            List<IntradayPointDTO> points = marketQueryService.stockIntraday(userId, stockId);
            StringBuilder sb = new StringBuilder("slot,time,price\n");
            for (int i = 0; i < points.size(); i++) {
                IntradayPointDTO p = points.get(i);
                sb.append(i).append(",");
                sb.append(p.getTime() == null ? "" : p.getTime()).append(",");
                sb.append(p.getPrice() == null ? "" : p.getPrice()).append("\n");
            }
            Files.writeString(Path.of("target", "vol-intraday-" + seed + "-" + stockId + ".csv"), sb.toString());
        }
    }

    private long worldSeed(long userId) {
        return gameState(userId).getWorldSeed();
    }

    /** 钉死世界种子：标定要跨轮次可比，就不能每轮换一局。 */
    private void setWorldSeed(long userId, long seed) {
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(seed);
        userGameStateMapper.updateById(gs);
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private static double std(List<Double> xs) {
        if (xs.size() < 2) {
            return 0;
        }
        double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = 0;
        for (double x : xs) {
            var += (x - mean) * (x - mean);
        }
        return Math.sqrt(var / (xs.size() - 1));
    }

    private static double meanAbs(List<Double> xs) {
        return xs.stream().mapToDouble(x -> Math.abs(x)).average().orElse(0);
    }

    private static double percentileAbs(List<Double> xs) {
        double[] sorted = xs.stream().mapToDouble(x -> Math.abs(x)).sorted().toArray();
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(0.99 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }
}