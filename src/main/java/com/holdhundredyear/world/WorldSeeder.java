package com.holdhundredyear.world;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.game.constants.NpcDefs;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.constants.PreHistoryDefs;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.market.entity.StockDailyBar;
import com.holdhundredyear.market.entity.StockInfo;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.StockDailyBarMapper;
import com.holdhundredyear.market.mapper.StockInfoMapper;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserEraMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 注册播种器：为新用户构建完整初始世界（spec §2 初始资金 10 万、§3 纪元、§4.4 剧本日、§5 NPC）。
 * 掷出的 worldSeed 落库，纪元/剧本日均可由该 seed 确定性重掷复现。
 */
@Service
public class WorldSeeder {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final SysUserMapper sysUserMapper;
    private final UserGameStateMapper userGameStateMapper;
    private final StockInfoMapper stockInfoMapper;
    private final UserStockStateMapper userStockStateMapper;
    private final UserEraMapper userEraMapper;
    private final UserScriptDayMapper userScriptDayMapper;
    private final UserDailySnapshotMapper userDailySnapshotMapper;
    private final StockDailyBarMapper stockDailyBarMapper;

    public WorldSeeder(SysUserMapper sysUserMapper,
                       UserGameStateMapper userGameStateMapper,
                       StockInfoMapper stockInfoMapper,
                       UserStockStateMapper userStockStateMapper,
                       UserEraMapper userEraMapper,
                       UserScriptDayMapper userScriptDayMapper,
                       UserDailySnapshotMapper userDailySnapshotMapper,
                       StockDailyBarMapper stockDailyBarMapper) {
        this.sysUserMapper = sysUserMapper;
        this.userGameStateMapper = userGameStateMapper;
        this.stockInfoMapper = stockInfoMapper;
        this.userStockStateMapper = userStockStateMapper;
        this.userEraMapper = userEraMapper;
        this.userScriptDayMapper = userScriptDayMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
        this.stockDailyBarMapper = stockDailyBarMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    /** 以标准模式注册（测试与老调用方沿用）。 */
    public long seedUser(String username, String bcryptPassword) {
        return seedUser(username, bcryptPassword, GameMode.STANDARD);
    }

    /** 以指定模式注册：模式随 game_state 落库，整局不变。 */
    public long seedUser(String username, String bcryptPassword, GameMode mode) {
        long worldSeed = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(bcryptPassword);
        user.setBalance(BigDecimal.valueOf(100000.00));
        user.setDebt(BigDecimal.valueOf(0.00));
        sysUserMapper.insert(user);
        long userId = user.getId();

        GameMode chosen = mode == null ? GameMode.STANDARD : mode;
        UserGameState state = new UserGameState();
        state.setUserId(userId);
        state.setCurrentDay(1);
        state.setMarketState("NOT_STARTED");
        state.setTickIndex(0);
        state.setWorldSeed(worldSeed);
        state.setGameMode(chosen.name());
        state.setIndexValue(1000.0);
        state.setPrevIndex(1000.0);
        state.setVolRegime("NORMAL");
        state.setTier("RETAIL");
        userGameStateMapper.insert(state);

        ensureStockInfoSeeded();

        seedWorld(userId, worldSeed);

        return userId;
    }

    /**
    /** 读本账户的本局模式；行缺失或值未知一律按标准模式。 */
    private GameMode modeOf(long userId) {
        UserGameState state = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        return state == null ? GameMode.STANDARD : GameMode.of(state.getGameMode());
    }

    /**
     * 播种一个已有账号的游戏世界（spec §2/§3/§4.4/§5）：股票状态 24 行 → 纪元 →
     * 剧本日 → day0 快照。注册（seedUser）与终局重开共用；调用方需保证该 userId
     * 的旧世界数据已清空、user/GameState 行已就绪。
     */
    @Transactional(rollbackFor = Exception.class)
    public void seedWorld(long userId, long worldSeed) {
        for (StockDefs.StockDef def : StockDefs.ALL) {
            BigDecimal price = BigDecimal.valueOf(def.basePrice());
            UserStockState stockState = new UserStockState();
            stockState.setUserId(userId);
            stockState.setStockId(def.id());
            stockState.setPrevClose(price);
            stockState.setOpenPrice(price);
            stockState.setHighPrice(price);
            stockState.setLowPrice(price);
            stockState.setCurrentPrice(price);
            stockState.setLimitUpStreak(0);
            stockState.setHalted(false);
            stockState.setHaltUntilDay(null);
            stockState.setIsSt(false);
            stockState.setGapPct(0.0);
            stockState.setVersion(0);
            userStockStateMapper.insert(stockState);
        }

        // 开局前的 10 根日K（game_day 0..-9，纯展示）：第 1 天点开个股页就有历史可看，
        // 也让日K图交代清"这只票开局前被怎么对待"。走势档案见 PreHistoryDefs。
        GameMode preHistoryMode = modeOf(userId);
        for (StockDefs.StockDef def : StockDefs.ALL) {
            for (StockDailyBar bar : buildPreHistoryBars(worldSeed, def, preHistoryMode)) {
                bar.setUserId(userId);
                stockDailyBarMapper.insert(bar);
            }
        }

        // 排期按本局模式掷：快速模式纪元密度翻倍，但 11 个纪元仍按序全覆盖
        List<EraScheduler.EraInstance> eras = new EraScheduler().roll(worldSeed, modeOf(userId));
        for (EraScheduler.EraInstance era : eras) {
            UserEra userEra = new UserEra();
            userEra.setUserId(userId);
            userEra.setEraIndex(era.eraIndex());
            userEra.setEraCode(era.code().name());
            userEra.setStartDay(era.startDay());
            userEra.setEndDay(era.endDay());
            userEra.setParamsJson(era.paramsJson());
            userEraMapper.insert(userEra);
        }

        for (ScriptDayScheduler.ScriptDayInstance scriptDay
                : new ScriptDayScheduler().roll(worldSeed, eras, modeOf(userId))) {
            UserScriptDay userScriptDay = new UserScriptDay();
            userScriptDay.setUserId(userId);
            userScriptDay.setGameDay(scriptDay.gameDay());
            userScriptDay.setScriptType(scriptDay.type().name());
            userScriptDay.setParamsJson(scriptDay.paramsJson());
            userScriptDayMapper.insert(userScriptDay);
        }

        UserDailySnapshot snapshot = new UserDailySnapshot();
        snapshot.setUserId(userId);
        snapshot.setGameDay(0);
        snapshot.setCash(BigDecimal.valueOf(100000.00));
        snapshot.setMarketValue(BigDecimal.valueOf(0.00));
        snapshot.setDebt(BigDecimal.valueOf(0.00));
        snapshot.setSharkDebt(BigDecimal.ZERO);
        snapshot.setNetAsset(BigDecimal.valueOf(100000.00));
        snapshot.setIndexValue(1000.0);
        snapshot.setNpcAssetsJson(npcAssetsJson());
        userDailySnapshotMapper.insert(snapshot);
    }

    /**
     * 一只票的开局前日K（{@link PreHistoryDefs#BARS} 根，game_day 0..-9）。
     *
     * <p>先按板块档案掷出逐日收益铺一段价格路径，再整体缩放，让最新一根（day 0）的收盘价
     * 正好等于发行价——日K图上"开盘前"与"第 1 天"之间因此没有断点，第 1 天的涨跌幅照旧拿
     * 发行价当昨收。单日收益夹在本局涨跌停幅度内：前史也得像个股，不能出现 -30% 的假单日。
     *
     * <p>掷骰用独立 salt（{@code "prehistory" + stockId}），与行情/事件/剧本序列互不消费，
     * 同 seed 必得同前史。包内可见是为了单测能直接断言形状与边界。
     */
    static List<StockDailyBar> buildPreHistoryBars(long worldSeed, StockDefs.StockDef def, GameMode mode) {
        PreHistoryDefs.Profile profile = PreHistoryDefs.of(def.sector());
        Random rng = WorldRandom.of(worldSeed, "prehistory" + def.id());
        double limit = mode.getLimitPct();
        int bars = PreHistoryDefs.BARS;

        // 1) 掷 bars 个日收益，铺出 bars+1 个收盘价（closes[0] 是最老的一天）
        double[] closes = new double[bars + 1];
        closes[0] = 1.0;
        for (int i = 1; i <= bars; i++) {
            double ret = profile.dailyDrift() + profile.dailyVol() * rng.nextGaussian();
            closes[i] = closes[i - 1] * (1 + Math.max(-limit, Math.min(limit, ret)));
        }
        // 2) 整体缩放到"最新一根收盘 = 发行价"，与本局昨收完全衔接
        double scale = def.basePrice() / closes[bars];

        List<StockDailyBar> out = new ArrayList<>(bars);
        for (int i = 1; i <= bars; i++) {
            double prevClose = round2(closes[i - 1] * scale);
            double close = round2(closes[i] * scale);
            double open = round2(Math.max(0.01, prevClose * (1 + (rng.nextDouble() - 0.5) * profile.dailyVol())));
            double bodyHigh = Math.max(open, close);
            double bodyLow = Math.min(open, close);
            // 上下影线夹在涨跌停价与实体之间：high 必 >= 实体上沿、low 必 <= 实体下沿
            double high = Math.max(Math.min(round2(prevClose * (1 + limit)),
                    round2(bodyHigh * (1 + rng.nextDouble() * profile.dailyVol() * 0.5))), bodyHigh);
            double low = Math.min(Math.max(round2(prevClose * (1 - limit)),
                    round2(bodyLow * (1 - rng.nextDouble() * profile.dailyVol() * 0.5))), bodyLow);

            StockDailyBar bar = new StockDailyBar();
            bar.setStockId(def.id());
            bar.setGameDay(i - bars); // 1 → -9 …… bars → 0
            bar.setOpen(BigDecimal.valueOf(open));
            bar.setHigh(BigDecimal.valueOf(high));
            bar.setLow(BigDecimal.valueOf(low));
            bar.setClose(BigDecimal.valueOf(close));
            bar.setChangePct(prevClose > 0 ? close / prevClose - 1 : 0.0);
            out.add(bar);
        }
        return out;
    }

    /** 落库口径：金额分位 HALF_UP（与结算/交易一致）。 */
    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 幂等播种 stock_info：24 只 StockDefs 以显式 id 写入，已存在则跳过。 */

    private void ensureStockInfoSeeded() {
        for (StockDefs.StockDef def : StockDefs.ALL) {
            StockInfo info = new StockInfo();
            info.setId(def.id());
            info.setStockCode(def.code());
            info.setStockName(def.name());
            info.setSector(def.sector());
            info.setBasePrice(BigDecimal.valueOf(def.basePrice()));
            info.setBeta(def.beta());
            info.setIndexWeight(def.weight());
            stockInfoMapper.insertIgnore(info);
        }
    }

    /** 8 个 NPC 的初始资产 JSON（字段顺序固定：code/name/asset/status）。 */
    private static String npcAssetsJson() {
        List<Map<String, Object>> npcs = new ArrayList<>(NpcDefs.ALL.size());
        for (NpcDefs.Npc npc : NpcDefs.ALL) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", npc.code());
            entry.put("name", npc.name());
            entry.put("asset", 100000.0);
            entry.put("status", "ACTIVE");
            npcs.add(entry);
        }
        return MAPPER.writeValueAsString(npcs);
    }
}
