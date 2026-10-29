package com.holdhundredyear.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.FastForwardService;
import com.holdhundredyear.market.GameEndService;
import com.holdhundredyear.market.entity.StockDailyBar;
import com.holdhundredyear.market.entity.StockInfo;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.mapper.StockDailyBarMapper;
import com.holdhundredyear.market.mapper.StockInfoMapper;
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

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据统计工具（不是测试，mvn test 默认不会跑）：跑 10 局标准模式，把每局每只股票 / ETF 的
 * 每日 OHLC 与涨跌幅、以及每日大盘指数导出成 CSV，供外部脚本生成 Excel 报表。
 *
 * <p>运行：{@code mvn -Dtest=MarketDataDumpTool test}（需要本机 hold_year_test）。
 * 固定 10 个世界种子，结果可复现；跑完用 {@link GameEndService#purgeSave} 清掉这 10 份临时存档。
 */
@SpringBootTest
class MarketDataDumpTool {

    /** 10 局的固定世界种子：换一批种子就是换一批行情，但同一批永远可复现。 */
    private static final long[] SEEDS = {
            2026102501L, 2026102502L, 2026102503L, 2026102504L, 2026102505L,
            2026102506L, 2026102507L, 2026102508L, 2026102509L, 2026102510L
    };

    private static final int GAMES = SEEDS.length;
    private static final int DAYS = GameMode.STANDARD.totalDays();
    private static final Path OUT = Paths.get("target", "market-dump");

    @Autowired private WorldSeeder worldSeeder;
    @Autowired private FastForwardService fastForwardService;
    @Autowired private GameEndService gameEndService;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserEraMapper userEraMapper;
    @Autowired private UserScriptDayMapper userScriptDayMapper;
    @Autowired private StockDailyBarMapper stockDailyBarMapper;
    @Autowired private StockInfoMapper stockInfoMapper;
    @Autowired private UserDailySnapshotMapper userDailySnapshotMapper;

    @Test
    void dumpTenGames() throws Exception {
        Files.createDirectories(OUT);
        Map<Long, StockDefs.StockDef> defs = new LinkedHashMap<>();
        for (StockDefs.StockDef def : StockDefs.ALL_INSTRUMENTS) {
            defs.put(def.id(), def);
        }
        Map<Long, StockInfo> infoById = new HashMap<>();
        for (StockInfo info : stockInfoMapper.selectList(null)) {
            infoById.put(info.getId(), info);
        }

        List<Long> users = new ArrayList<>();
        try (BufferedWriter daily = Files.newBufferedWriter(OUT.resolve("daily.csv"), StandardCharsets.UTF_8);
             BufferedWriter games = Files.newBufferedWriter(OUT.resolve("games.csv"), StandardCharsets.UTF_8)) {
            daily.write("game,seed,gameDay,stockId,code,name,sector,kind,open,high,low,close,prevClose,changePct,"
                    + "indexValue,indexDayPct\n");
            games.write("game,seed,finalIndex,indexTotalReturnPct,days\n");

            for (int g = 0; g < GAMES; g++) {
                long userId = worldSeeder.seedUser("dump_" + g, "$2a$10$bcrypthash");
                users.add(userId);
                setWorldSeed(userId, SEEDS[g]);
                for (int day = 0; day < DAYS; day++) {
                    fastForwardService.skip(userId);
                }

                UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                        .eq(UserGameState::getUserId, userId));
                Map<Integer, UserDailySnapshot> snapshotByDay = new HashMap<>();
                for (UserDailySnapshot s : userDailySnapshotMapper.selectList(
                        new LambdaQueryWrapper<UserDailySnapshot>().eq(UserDailySnapshot::getUserId, userId))) {
                    snapshotByDay.put(s.getGameDay(), s);
                }

                List<StockDailyBar> bars = stockDailyBarMapper.selectList(
                        new LambdaQueryWrapper<StockDailyBar>()
                                .eq(StockDailyBar::getUserId, userId)
                                .gt(StockDailyBar::getGameDay, 0)
                                .orderByAsc(StockDailyBar::getGameDay)
                                .orderByAsc(StockDailyBar::getStockId));
                Map<Long, Double> lastClose = new HashMap<>();
                for (StockDailyBar bar : bars) {
                    StockDefs.StockDef def = defs.get(bar.getStockId());
                    if (def == null) {
                        continue;
                    }
                    double close = bar.getClose().doubleValue();
                    double prevClose = lastClose.getOrDefault(bar.getStockId(), def.basePrice());
                    lastClose.put(bar.getStockId(), close);
                    UserDailySnapshot snap = snapshotByDay.get(bar.getGameDay());
                    double indexValue = snap != null ? snap.getIndexValue() : 0.0;
                    double indexDayPct = snap != null && snap.getIndexDayPct() != null
                            ? snap.getIndexDayPct() : 0.0;
                    daily.write(String.join(",",
                            String.valueOf(g + 1),
                            String.valueOf(SEEDS[g]),
                            String.valueOf(bar.getGameDay()),
                            String.valueOf(bar.getStockId()),
                            csv(def.code()),
                            csv(def.name()),
                            csv(def.sector()),
                            StockDefs.isEtf(def.id()) ? "ETF" : "STOCK",
                            num(bar.getOpen().doubleValue()),
                            num(bar.getHigh().doubleValue()),
                            num(bar.getLow().doubleValue()),
                            num(close),
                            num(prevClose),
                            num(bar.getChangePct() != null ? bar.getChangePct() * 100 : 0.0),
                            num(indexValue),
                            num(indexDayPct)));
                    daily.write('\n');
                }
                double finalIndex = gs.getIndexValue();
                games.write(String.join(",",
                        String.valueOf(g + 1),
                        String.valueOf(SEEDS[g]),
                        num(finalIndex),
                        num((finalIndex / GameConstants.INITIAL_INDEX - 1) * 100),
                        String.valueOf(DAYS)));
                games.write('\n');
                System.out.println("DUMP game " + (g + 1) + "/" + GAMES + " done, index=" + finalIndex);
            }
        } finally {
            for (long userId : users) {
                gameEndService.purgeSave(userId);
            }
        }
        System.out.println("DUMP written to " + OUT.toAbsolutePath());
    }

    /** 固定种子：改 game_state 的种子后必须按同一种子重掷纪元与剧本日，否则行情与日历对不上。 */
    private void setWorldSeed(long userId, long seed) {
        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setWorldSeed(seed);
        gs.setIndexValue(GameConstants.INITIAL_INDEX);
        gs.setPrevIndex(GameConstants.INITIAL_INDEX);
        userGameStateMapper.updateById(gs);

        userEraMapper.delete(new LambdaQueryWrapper<UserEra>().eq(UserEra::getUserId, userId));
        List<EraScheduler.EraInstance> eras = new EraScheduler().roll(seed, GameMode.STANDARD);
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
        List<ScriptDayScheduler.ScriptDayInstance> days =
                new ScriptDayScheduler().roll(seed, eras, GameMode.STANDARD);
        for (ScriptDayScheduler.ScriptDayInstance day : days) {
            UserScriptDay row = new UserScriptDay();
            row.setUserId(userId);
            row.setGameDay(day.gameDay());
            row.setScriptType(day.type().name());
            row.setParamsJson(day.paramsJson());
            userScriptDayMapper.insert(row);
        }
    }

    private static String num(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        return s.contains(",") ? '"' + s + '"' : s;
    }
}
