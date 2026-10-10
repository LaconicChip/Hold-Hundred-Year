package com.holdhundredyear.world;

import com.holdhundredyear.game.constants.NpcDefs;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.entity.StockInfo;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.StockInfoMapper;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserEraMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldSeeder 集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 核心保证：以库中落盘的 worldSeed 重掷 Era/ScriptDay 调度器，逐字段比对 DB 行。
 */
@SpringBootTest
@Transactional
class WorldSeederTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private StockInfoMapper stockInfoMapper;

    @Autowired
    private UserStockStateMapper userStockStateMapper;

    @Autowired
    private UserEraMapper userEraMapper;

    @Autowired
    private UserScriptDayMapper userScriptDayMapper;

    @Autowired
    private UserDailySnapshotMapper userDailySnapshotMapper;

    @Test
    void seedUserCreatesSysUserWithInitialBalanceAndZeroDebt() {
        long userId = worldSeeder.seedUser("seeder_user", "$2a$10$bcrypthash");

        SysUser user = sysUserMapper.selectById(userId);
        assertNotNull(user, "sys_user must exist after seeding");
        assertEquals("seeder_user", user.getUsername());
        assertEquals("$2a$10$bcrypthash", user.getPassword());
        assertEquals(0, new BigDecimal("100000.00").compareTo(user.getBalance()), "balance");
        assertEquals(0, BigDecimal.ZERO.compareTo(user.getDebt()), "debt");
    }

    @Test
    void seedUserCreatesDayOneNotStartedGameState() {
        long userId = worldSeeder.seedUser("seeder_state", "$2a$10$bcrypthash");

        List<UserGameState> states = userGameStateMapper.selectList(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        assertEquals(1, states.size(), "exactly one user_game_state row");
        UserGameState state = states.get(0);
        assertEquals(1, state.getCurrentDay());
        assertEquals("NOT_STARTED", state.getMarketState());
        assertEquals(0, state.getTickIndex());
        assertNotNull(state.getWorldSeed(), "worldSeed must be stored");
        assertTrue(state.getWorldSeed() != 0, "worldSeed must be non-zero");
        assertEquals(1000.0, state.getIndexValue(), 0.0);
        assertEquals("NORMAL", state.getVolRegime());
        assertEquals("RETAIL", state.getTier());
        assertNull(state.getPrevTotalAsset());
        assertNull(state.getEndReason());
        assertNull(state.getEndDay());
    }

    @Test
    void seedUserCreatesTwentyFourStockStatesAtBasePrice() {
        long userId = worldSeeder.seedUser("seeder_stocks", "$2a$10$bcrypthash");

        List<UserStockState> states = userStockStateMapper.selectList(
                new LambdaQueryWrapper<UserStockState>().eq(UserStockState::getUserId, userId)
                        .orderByAsc(UserStockState::getStockId));
        assertEquals(24, states.size(), "24 user_stock_state rows");

        Set<Long> expectedIds = StockDefs.ALL.stream().map(StockDefs.StockDef::id).collect(Collectors.toSet());
        Set<Long> actualIds = states.stream().map(UserStockState::getStockId).collect(Collectors.toSet());
        assertEquals(expectedIds, actualIds, "stock ids must match StockDefs");

        for (UserStockState state : states) {
            StockDefs.StockDef def = StockDefs.ALL.stream()
                    .filter(s -> s.id() == state.getStockId()).findFirst().orElseThrow();
            BigDecimal base = BigDecimal.valueOf(def.basePrice());
            assertEquals(0, base.compareTo(state.getPrevClose()), def.name() + " prevClose");
            assertEquals(0, base.compareTo(state.getOpenPrice()), def.name() + " openPrice");
            assertEquals(0, base.compareTo(state.getHighPrice()), def.name() + " highPrice");
            assertEquals(0, base.compareTo(state.getLowPrice()), def.name() + " lowPrice");
            assertEquals(0, base.compareTo(state.getCurrentPrice()), def.name() + " currentPrice");
            assertEquals(0, state.getLimitUpStreak(), def.name() + " limitUpStreak");
            assertFalse(state.getHalted(), def.name() + " halted");
            assertEquals(null, state.getHaltUntilDay(), def.name() + " haltUntilDay");
            assertFalse(state.getIsSt(), def.name() + " isSt");
            assertEquals(0.0, state.getGapPct(), 0.0, def.name() + " gapPct");
            assertEquals(0, state.getVersion(), def.name() + " version");
        }

        UserStockState stock1 = states.stream()
                .filter(s -> s.getStockId() == 1L).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("168.00").compareTo(stock1.getCurrentPrice()), "stock 1 price");
    }

    @Test
    void seedUserSeedsStockInfoWithCanonicalIdsOneToTwentyFour() {
        worldSeeder.seedUser("seeder_info", "$2a$10$bcrypthash");

        List<StockInfo> infos = stockInfoMapper.selectList(null);
        assertEquals(24, infos.size(), "24 stock_info rows");

        Set<Long> actualIds = infos.stream().map(StockInfo::getId).collect(Collectors.toSet());
        assertEquals(24, actualIds.size(), "stock_info ids must be distinct");
        for (long expected = 1; expected <= 24; expected++) {
            assertTrue(actualIds.contains(expected), "stock_info must contain id " + expected);
        }

        for (StockDefs.StockDef def : StockDefs.ALL) {
            StockInfo info = infos.stream()
                    .filter(i -> i.getId() == def.id()).findFirst().orElseThrow();
            assertEquals(def.code(), info.getStockCode(), def.name() + " stockCode");
            assertEquals(def.name(), info.getStockName(), def.name() + " stockName");
            assertEquals(def.sector(), info.getSector(), def.name() + " sector");
            assertEquals(0, BigDecimal.valueOf(def.basePrice()).compareTo(info.getBasePrice()),
                    def.name() + " basePrice");
            assertEquals(def.beta(), info.getBeta(), 0.0, def.name() + " beta");
            assertEquals(def.weight(), info.getIndexWeight(), 1e-9, def.name() + " indexWeight");
        }
    }

    @Test
    void quickModeRegistrationPersistsModeAndCompressedSchedule() {
        long userId = worldSeeder.seedUser("seeder_quick1", "$2a$10$bcrypthash", GameMode.QUICK);

        UserGameState state = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        assertEquals("QUICK", state.getGameMode(), "mode must be stored with the account for the whole run");

        List<UserEra> eras = userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                .eq(UserEra::getUserId, userId).orderByAsc(UserEra::getEraIndex));
        assertEquals(11, eras.size(), "quick mode keeps all 11 eras");
        assertEquals(1, eras.get(0).getStartDay());
        assertEquals(36, eras.get(10).getEndDay(), "quick mode finishes on day 36, not 72");

        // 老签名（不传模式）必须仍是标准模式，存量行为零变化
        long standard = worldSeeder.seedUser("seeder_quick2", "$2a$10$bcrypthash");
        UserGameState standardState = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, standard));
        assertEquals("STANDARD", standardState.getGameMode());
    }

    @Test
    void seedUserPersistsErasExactlyMatchingReRolledSchedule() {
        long userId = worldSeeder.seedUser("seeder_era", "$2a$10$bcrypthash");

        UserGameState state = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        long worldSeed = state.getWorldSeed();

        List<UserEra> userEras = userEraMapper.selectList(
                new LambdaQueryWrapper<UserEra>().eq(UserEra::getUserId, userId)
                        .orderByAsc(UserEra::getEraIndex));
        assertEquals(11, userEras.size(), "11 user_era rows");

        List<EraScheduler.EraInstance> expected = new EraScheduler().roll(worldSeed);
        assertEquals(11, expected.size());
        for (int i = 0; i < 11; i++) {
            UserEra db = userEras.get(i);
            EraScheduler.EraInstance exp = expected.get(i);
            assertEquals(exp.eraIndex(), db.getEraIndex(), "eraIndex at position " + i);
            assertEquals(exp.code().name(), db.getEraCode(), "eraCode at position " + i);
            assertEquals(exp.startDay(), db.getStartDay(), "startDay at position " + i);
            assertEquals(exp.endDay(), db.getEndDay(), "endDay at position " + i);
            assertEquals(exp.paramsJson(), db.getParamsJson(), "paramsJson at position " + i);
        }

        for (int i = 0; i < 11; i++) {
            assertEquals(i + 1, userEras.get(i).getEraIndex(), "eraIndex must be 1..11 in order");
        }
        for (int i = 0; i < 10; i++) {
            assertEquals(userEras.get(i).getEndDay() + 1, userEras.get(i + 1).getStartDay(),
                    "era boundaries must be consecutive between era " + (i + 1) + " and " + (i + 2));
        }
        assertEquals(GameMode.STANDARD.getDaysPerYear() + 1, userEras.get(4).getStartDay(),
                "era5 must open year 2");
    }

    @Test
    void seedUserPersistsScriptDaysExactlyMatchingReRolledSchedule() {
        long userId = worldSeeder.seedUser("seeder_script", "$2a$10$bcrypthash");

        UserGameState state = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        long worldSeed = state.getWorldSeed();

        List<EraScheduler.EraInstance> eras = new EraScheduler().roll(worldSeed);
        List<ScriptDayScheduler.ScriptDayInstance> expected =
                new ScriptDayScheduler().roll(worldSeed, eras);
        assertFalse(expected.isEmpty());

        Set<String> expectedTuples = expected.stream()
                .map(d -> d.gameDay() + "|" + d.type().name() + "|" + d.paramsJson())
                .collect(Collectors.toSet());

        List<UserScriptDay> userScriptDays = userScriptDayMapper.selectList(
                new LambdaQueryWrapper<UserScriptDay>().eq(UserScriptDay::getUserId, userId));
        assertEquals(expected.size(), userScriptDays.size(), "user_script_day row count must match re-roll");

        Set<String> actualTuples = new HashSet<>();
        for (UserScriptDay day : userScriptDays) {
            actualTuples.add(day.getGameDay() + "|" + day.getScriptType() + "|" + day.getParamsJson());
        }
        assertEquals(expectedTuples, actualTuples, "user_script_day must equal re-rolled schedule");
    }

    @Test
    void seedUserCreatesDayZeroSnapshotWithEightActiveNpcs() {
        long userId = worldSeeder.seedUser("seeder_snapshot", "$2a$10$bcrypthash");

        List<UserDailySnapshot> snapshots = userDailySnapshotMapper.selectList(
                new LambdaQueryWrapper<UserDailySnapshot>().eq(UserDailySnapshot::getUserId, userId));
        assertEquals(1, snapshots.size(), "exactly one user_daily_snapshot row");
        UserDailySnapshot snapshot = snapshots.get(0);

        assertEquals(0, snapshot.getGameDay());
        assertEquals(0, new BigDecimal("100000.00").compareTo(snapshot.getCash()), "cash");
        assertEquals(0, BigDecimal.ZERO.compareTo(snapshot.getMarketValue()), "marketValue");
        assertEquals(0, BigDecimal.ZERO.compareTo(snapshot.getDebt()), "debt");
        assertEquals(0, new BigDecimal("100000.00").compareTo(snapshot.getNetAsset()), "netAsset");
        assertNull(snapshot.getDayReturnPct(), "dayReturnPct");
        assertEquals(1000.0, snapshot.getIndexValue(), 0.0);
        assertNull(snapshot.getIndexDayPct(), "indexDayPct");

        String npcJson = snapshot.getNpcAssetsJson();
        assertTrue(npcJson.startsWith("[{\"code\":\"DAMA\",\"name\":\"张大妈\",\"asset\":100000.0,\"status\":\"ACTIVE\"}"),
                "npcAssetsJson must start with the first NPC, fields in fixed order");
        JsonNode npcs = MAPPER.readTree(npcJson);
        assertTrue(npcs.isArray(), "npcAssetsJson must be a JSON array");
        assertEquals(8, npcs.size(), "8 NPCs");
        for (int i = 0; i < 8; i++) {
            JsonNode npc = npcs.get(i);
            assertEquals(NpcDefs.ALL.get(i).code(), npc.get("code").asString(), "NPC " + i + " code");
            assertEquals(NpcDefs.ALL.get(i).name(), npc.get("name").asString(), "NPC " + i + " name");
            assertEquals(100000.0, npc.get("asset").asDouble(), 1e-9, "NPC " + i + " asset");
            assertEquals("ACTIVE", npc.get("status").asString(), "NPC " + i + " status");
        }
    }
}
