package com.holdhunderdyear.world;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.game.constants.NpcDefs;
import com.holdhunderdyear.game.model.enums.GameMode;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.market.entity.StockInfo;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserEra;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserScriptDay;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.StockInfoMapper;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserEraMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserScriptDayMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public WorldSeeder(SysUserMapper sysUserMapper,
                       UserGameStateMapper userGameStateMapper,
                       StockInfoMapper stockInfoMapper,
                       UserStockStateMapper userStockStateMapper,
                       UserEraMapper userEraMapper,
                       UserScriptDayMapper userScriptDayMapper,
                       UserDailySnapshotMapper userDailySnapshotMapper) {
        this.sysUserMapper = sysUserMapper;
        this.userGameStateMapper = userGameStateMapper;
        this.stockInfoMapper = stockInfoMapper;
        this.userStockStateMapper = userStockStateMapper;
        this.userEraMapper = userEraMapper;
        this.userScriptDayMapper = userScriptDayMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
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
        snapshot.setNetAsset(BigDecimal.valueOf(100000.00));
        snapshot.setIndexValue(1000.0);
        snapshot.setNpcAssetsJson(npcAssetsJson());
        userDailySnapshotMapper.insert(snapshot);
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
