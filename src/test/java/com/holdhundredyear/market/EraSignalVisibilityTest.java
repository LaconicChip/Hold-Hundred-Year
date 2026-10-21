package com.holdhundredyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.entity.StockDailyBar;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.mapper.StockDailyBarMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserEraMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.world.EraScheduler;
import com.holdhundredyear.world.ScriptDayScheduler;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纪元主线可见度闸门（2026-10-21 新增）：玩家要能"看得见"市场基调与领涨/领跌板块，
 * 而不是只看到一堆无理由的随机波动。
 *
 * <p>做法：固定三个世界种子跑完整局，按 {@code user_era} 把每个股票日归到所属纪元，
 * 再按<b>该局掷出的板块系数</b>把股票日分成"领涨 / 领跌 / 未点名"三组，比较各组
 * 相对当日指数的平均超额收益。剧本日（千股涨跌停/熔断/妖股）不参与，否则名场面会
 * 把有机行情的主线淹掉。
 *
 * <p><b>为什么按"股票日"汇总而不是按"纪元均值"比较：</b>单个纪元只有 2~16 天、
 * 每个板块 1~2 只票，日噪声 5% 远大于每日 1%~3% 的漂移，单纪元均值会被随机事件
 * 带偏（实测 era 2 的领涨组能因为一只妖股 -3% 而整体翻负）。把三个种子的全部
 * 有机股票日池在一起，样本从几十涨到几千，才能稳定判断"方向有没有跟着剧本走"。
 *
 * <p>这不是"波动大小"的闸门（那个看 {@link VolatilityDistributionTest}），而是
 * "方向有没有跟着剧本走"的闸门。两条线要一起看：波动调大很容易把主线一起稀释。
 */
@SpringBootTest
@Transactional
class EraSignalVisibilityTest {

    private static final int RUNS = 3;
    private static final long[] WORLD_SEEDS = {20261020L, 20261021L, 20261022L};

    /** 牛市基调纪元（指数应向上）。 */
    private static final Set<Integer> BULL_ERAS = Set.of(2, 9);
    /** 熊市基调纪元（指数应向下）。 */
    private static final Set<Integer> BEAR_ERAS = Set.of(3, 5, 7, 10);

    @Autowired private WorldSeeder worldSeeder;
    @Autowired private FastForwardService fastForwardService;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserEraMapper userEraMapper;
    @Autowired private UserScriptDayMapper userScriptDayMapper;
    @Autowired private StockDailyBarMapper stockDailyBarMapper;
    @Autowired private UserDailySnapshotMapper userDailySnapshotMapper;

    @Test
    void eraThemesAreVisibleInSectorReturns() {
        Pool pool = new Pool();
        for (int run = 0; run < RUNS; run++) {
            long userId = worldSeeder.seedUser("era_sig_" + run, "$2a$10$bcrypthash");
            setWorldSeed(userId, WORLD_SEEDS[run]);
            for (int day = 1; day <= GameMode.STANDARD.totalDays(); day++) {
                fastForwardService.skip(userId);
            }
            accumulate(userId, pool);
        }
        printAndAssert(pool);
    }

    private void accumulate(long userId, Pool pool) {
        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        GameMode mode = GameMode.of(gs.getGameMode());
        // 与库里的 user_era 同 seed 同模式重掷：拿到的就是引擎当日在用的板块系数
        Map<Integer, Map<String, Double>> coefsByEra = new HashMap<>();
        for (EraScheduler.EraInstance era : new EraScheduler().roll(gs.getWorldSeed(), mode)) {
            coefsByEra.put(era.eraIndex(), era.sectorCoefs());
        }

        Map<Integer, UserEra> byDay = new HashMap<>();
        for (UserEra era : userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                .eq(UserEra::getUserId, userId))) {
            for (int day = era.getStartDay(); day <= era.getEndDay(); day++) {
                byDay.put(day, era);
            }
        }

        Set<Integer> scripted = new HashSet<>();
        for (UserScriptDay row : userScriptDayMapper.selectList(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId))) {
            scripted.add(row.getGameDay());
        }

        Map<Integer, Double> indexByDay = new HashMap<>();
        for (UserDailySnapshot snap : userDailySnapshotMapper.selectList(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .ge(UserDailySnapshot::getGameDay, 1))) {
            // index_day_pct 存的是百分数（0.58 = 0.58%），个股 change_pct 是小数（0.0058），
            // 统一成小数口径再相减，否则"超额收益"会凭空多出几十个百分点。
            indexByDay.put(snap.getGameDay(),
                    snap.getIndexDayPct() == null ? 0.0 : snap.getIndexDayPct() / 100.0);
        }

        Set<Integer> marketCounted = new HashSet<>();
        List<StockDailyBar> bars = stockDailyBarMapper.selectList(new LambdaQueryWrapper<StockDailyBar>()
                .eq(StockDailyBar::getUserId, userId)
                .ge(StockDailyBar::getGameDay, 1));
        for (StockDailyBar bar : bars) {
            int day = bar.getGameDay();
            UserEra era = byDay.get(day);
            if (era == null) {
                continue;
            }
            // 市场基调要算上剧本日：千股跌停/熔断正是玩家感知"股灾"的主要来源；
            // 板块超额收益才排除剧本日，否则强制钉板会把个股性格完全淹没。
            if (marketCounted.add(day)) {
                pool.addMarket(era.getEraIndex(), indexByDay.getOrDefault(day, 0.0));
            }
            if (scripted.contains(day)) {
                continue;
            }
            double high = bar.getHigh().doubleValue();
            double low = bar.getLow().doubleValue();
            double change = bar.getChangePct() == null ? 0.0 : bar.getChangePct();
            // 停牌日：价格全天冻结，不参与"板块性格"统计
            if (high == low && change == 0.0) {
                continue;
            }
            StockDefs.StockDef def = StockDefs.byId(bar.getStockId());
            double market = indexByDay.getOrDefault(day, 0.0);
            double excess = change - market;
            double coef = coefsByEra.getOrDefault(era.getEraIndex(), Map.of())
                    .getOrDefault(def.sector(), 0.0);
            pool.add(coef, excess);
        }
    }

    private void printAndAssert(Pool pool) {
        System.out.println(String.format(
                "ERA-POOL positive=%s (n=%d) negative=%s (n=%d) unlisted=%s (n=%d) spread=%s",
                pct(pool.positive.mean()), pool.positive.count,
                pct(pool.negative.mean()), pool.negative.count,
                pct(pool.unlisted.mean()), pool.unlisted.count,
                pct(pool.positive.mean() - pool.negative.mean())));
        System.out.println(String.format(
                "ERA-TONE bull=%s (n=%d) bear=%s (n=%d) neutral=%s (n=%d)",
                pct(pool.bullMarket.mean()), pool.bullMarket.count,
                pct(pool.bearMarket.mean()), pool.bearMarket.count,
                pct(pool.neutralMarket.mean()), pool.neutralMarket.count));

        // 主线方向：被纪元点名的领涨板块，平均每天要跑赢指数；领跌板块要跑输。
        assertTrue(pool.positive.count > 300 && pool.negative.count > 300,
                "the sample must cover enough stock-days, got positive=" + pool.positive.count
                        + " negative=" + pool.negative.count);
        assertTrue(pool.positive.mean() > 0.003,
                "named leaders must outrun the index, got " + pct(pool.positive.mean()));
        assertTrue(pool.negative.mean() < -0.006,
                "named laggards must underperform the index, got " + pct(pool.negative.mean()));
        assertTrue(pool.positive.mean() - pool.negative.mean() > 0.009,
                "leader-vs-laggard spread must stay visible, got "
                        + pct(pool.positive.mean() - pool.negative.mean()));

        // 市场基调：牛市纪元向上、股灾/熔断/贸易战/黑7月向下。
        assertTrue(pool.bullMarket.mean() > 0.010,
                "bull eras must drift up, got " + pct(pool.bullMarket.mean()));
        assertTrue(pool.bearMarket.mean() < -0.005,
                "bear eras must drift down, got " + pct(pool.bearMarket.mean()));
        assertTrue(pool.bullMarket.mean() > pool.neutralMarket.mean(),
                "bull eras must beat the neutral eras");
    }

    private static String pct(double value) {
        return String.format("%.2f%%", value * 100);
    }

    /**
     * 把世界种子钉死，并<b>同步重掷</b> user_era / user_script_day。
     *
     * <p>只改 game_state.world_seed 是不够的：引擎按种子里重掷纪元（EraScheduler），
     * 而播种时写进库的 era/script 行还是旧种子的。两边不一致时，测试拿库里的纪元
     * 给日子分类、引擎却按另一套纪元在漂移，得到的结论完全是错的。
     */
    private void setWorldSeed(long userId, long seed) {
        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setWorldSeed(seed);
        userGameStateMapper.updateById(gs);
        GameMode mode = GameMode.of(gs.getGameMode());

        List<EraScheduler.EraInstance> eras = new EraScheduler().roll(seed, mode);
        userEraMapper.delete(new LambdaQueryWrapper<UserEra>().eq(UserEra::getUserId, userId));
        for (EraScheduler.EraInstance era : eras) {
            UserEra row = new UserEra();
            row.setUserId(userId);
            row.setEraIndex(era.eraIndex());
            row.setEraCode(era.code().name());
            row.setStartDay(era.startDay());
            row.setEndDay(era.endDay());
            row.setParamsJson(era.paramsJson());
            userEraMapper.insert(row);
        }

        userScriptDayMapper.delete(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId));
        for (ScriptDayScheduler.ScriptDayInstance day : new ScriptDayScheduler().roll(seed, eras, mode)) {
            UserScriptDay row = new UserScriptDay();
            row.setUserId(userId);
            row.setGameDay(day.gameDay());
            row.setScriptType(day.type().name());
            row.setParamsJson(day.paramsJson());
            userScriptDayMapper.insert(row);
        }
    }

    /** 全部股票日的池子：按板块系数正负分组，另按纪元基调汇总指数日收益。 */
    private static final class Pool {
        private final Stat positive = new Stat();
        private final Stat negative = new Stat();
        private final Stat unlisted = new Stat();
        private final Stat bullMarket = new Stat();
        private final Stat bearMarket = new Stat();
        private final Stat neutralMarket = new Stat();

        void add(double coef, double excess) {
            if (coef > 0) {
                positive.add(excess);
            } else if (coef < 0) {
                negative.add(excess);
            } else {
                unlisted.add(excess);
            }
        }

        void addMarket(int eraIndex, double market) {
            if (BULL_ERAS.contains(eraIndex)) {
                bullMarket.add(market);
            } else if (BEAR_ERAS.contains(eraIndex)) {
                bearMarket.add(market);
            } else {
                neutralMarket.add(market);
            }
        }
    }

    private static final class Stat {
        private double sum;
        private int count;

        void add(double value) {
            sum += value;
            count++;
        }

        double mean() {
            return count == 0 ? 0.0 : sum / count;
        }
    }
}
