package com.holdhundredyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.game.constants.NpcDefs;
import com.holdhundredyear.game.model.enums.SealState;
import com.holdhundredyear.game.model.enums.VolRegime;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.SettlementService;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC 服务（Task 8.4）集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖：静态纯函数的统计行为（nextAsset 可复现、damaDeclines ≈65%、laoliParkingTrigger 布尔、
 * blackJulyWithdraw 销户状态），以及收盘集成（NPC 资产演进落快照、同 seed 两世界一致、
 * 张大妈荐股消息、老李停车场消息、@玩家龙虎榜/割肉与每日限流）。
 */
@SpringBootTest
@Transactional
class NpcServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private NpcService npcService;

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private QuoteBoardRegistry quoteBoardRegistry;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserDailySnapshotMapper userDailySnapshotMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private TradeRecordMapper tradeRecordMapper;

    // ---- 静态纯函数 ----

    @Test
    void nextAssetIsReproduciblePerSeed() {
        double prev = 100000.0;
        Random r1 = new Random(99);
        Random r2 = new Random(99);
        double s1a = NpcService.nextAsset(prev, 1.2, 0.0, 0.006, 0.02, r1);
        double s1b = NpcService.nextAsset(prev, 1.2, 0.0, 0.006, 0.02, r1);
        double s2a = NpcService.nextAsset(prev, 1.2, 0.0, 0.006, 0.02, r2);
        double s2b = NpcService.nextAsset(prev, 1.2, 0.0, 0.006, 0.02, r2);
        assertEquals(s1a, s2a, 0.0, "same seed must reproduce the first step");
        assertEquals(s1b, s2b, 0.0, "same seed must reproduce the whole sequence");
        assertNotEquals(s1a, s1b, "the asset must keep evolving step to step");
        double other = NpcService.nextAsset(prev, 1.2, 0.0, 0.006, 0.02, new Random(101));
        assertNotEquals(s1a, other, "a different seed must diverge");
    }

    @Test
    void damaDeclinesFrequencyStaysNear65Percent() {
        Random rng = new Random(12345);
        int hits = 0;
        for (int i = 0; i < 5000; i++) {
            if (NpcService.damaDeclines(rng)) {
                hits++;
            }
        }
        double freq = hits / 5000.0;
        assertTrue(freq >= 0.62 && freq <= 0.68,
                "damaDeclines frequency must stay near 0.65, got " + freq);
    }

    @Test
    void laoliParkingTriggerIsBooleanAndConditional() {
        assertTrue(NpcService.laoliParkingTrigger(true, VolRegime.WILD),
                "full position in a WILD regime must trigger");
        assertFalse(NpcService.laoliParkingTrigger(false, VolRegime.WILD),
                "not full position must not trigger even in WILD");
        assertFalse(NpcService.laoliParkingTrigger(true, VolRegime.NORMAL),
                "full position in a calm regime must not trigger");
    }

    @Test
    void blackJulyWithdrawMarksOnlySettableNpcs() {
        assertEquals("天台排队中", NpcService.blackJulyWithdraw("LAOWANG", true));
        assertEquals("已销户", NpcService.blackJulyWithdraw("XINJIMIN", true));
        for (NpcDefs.Npc npc : NpcDefs.ALL) {
            if ("LAOWANG".equals(npc.code()) || "XINJIMIN".equals(npc.code())) {
                continue;
            }
            assertEquals("ACTIVE", NpcService.blackJulyWithdraw(npc.code(), true),
                    npc.code() + " must stay ACTIVE in black July");
        }
        assertEquals("ACTIVE", NpcService.blackJulyWithdraw("LAOWANG", false),
                "outside black July even a withdraw NPC must stay ACTIVE");
    }

    // ---- 集成：资产演进与角色线 ----

    @Test
    void settleWritesEightActiveNpcAssetsToSnapshot() {
        long userId = seedFixed(20260904L);
        toSettling(userId, 1);

        settlementService.settle(userId);

        JsonNode npcs = snapshotJson(userId, 1);
        assertNotNull(npcs);
        assertEquals(8, npcs.size(), "day snapshot must carry all 8 NPCs");
        for (int i = 0; i < npcs.size(); i++) {
            JsonNode npc = npcs.get(i);
            assertTrue(npc.has("code") && npc.has("name") && npc.has("asset") && npc.has("status"),
                    "each NPC row must carry code/name/asset/status");
            assertTrue(npc.get("asset").asDouble() > 0, "NPC asset must be positive");
            assertEquals("ACTIVE", npc.get("status").asString(), "non-black-July NPC must stay ACTIVE");
        }
        List<String> codes = codesOf(npcs);
        List<String> expected = NpcDefs.ALL.stream().map(NpcDefs.Npc::code).toList();
        assertEquals(expected, codes, "NPC order must follow NpcDefs.ALL");
    }

    @Test
    void sameSeedTwoWorldsProduceIdenticalNpcJson() {
        long a = seedFixed(20260203L);
        long b = seedFixed(20260203L);
        UserGameState gsA = gameState(a);
        UserGameState gsB = gameState(b);
        QuoteBoard boardA = quoteBoardRegistry.getOrLoad(a);
        QuoteBoard boardB = quoteBoardRegistry.getOrLoad(b);

        String jsonA = npcService.dailyAssetsJson(a, 1, gsA, boardA, 0.02);
        String jsonB = npcService.dailyAssetsJson(b, 1, gsB, boardB, 0.02);

        assertEquals(jsonA, jsonB, "the same seed on two worlds must yield identical NPC assets json");
    }

    @Test
    void damaHallShowsRecommendsOnSettledDays() {
        long userId = seedFixed(20260904L);
        UserMessage reco = null;
        for (int day = 1; day <= 12 && reco == null; day++) {
            toSettling(userId, day);
            settlementService.settle(userId);
            reco = messages(userId).stream()
                    .filter(m -> "NPC".equals(m.getSource()) && m.getTitle().contains("张大妈"))
                    .findFirst().orElse(null);
        }
        assertNotNull(reco, "张大妈 must post a recommendation within 12 settled days");
        assertEquals("NPC", reco.getSource());
        assertEquals("NOTIFY", reco.getType());
        assertFalse(reco.getIsRead());
        assertTrue(reco.getTitle().contains("张大妈"));
    }

    @Test
    void laoliParkingWarnsWhenFullPositionInWildRegime() {
        long userId = seedFixed(20260904L);
        SysUser user = sysUserMapper.selectById(userId);
        user.setBalance(BigDecimal.valueOf(1.00));
        sysUserMapper.updateById(user);
        quoteBoardRegistry.getOrLoad(userId).setVolRegime(VolRegime.WILD);
        toSettling(userId, 2);

        settlementService.settle(userId);

        UserMessage warning = messages(userId).stream()
                .filter(m -> m.getTitle().contains("老李停车场"))
                .findFirst().orElse(null);
        assertNotNull(warning, "a fully invested player in a WILD regime must get 老李 parking warning");
        assertEquals("NPC", warning.getSource());
        assertEquals("NOTIFY", warning.getType());
    }

    @Test
    void laoliParkingStaysSilentWhenRegimeCalm() {
        long userId = seedFixed(20260904L);
        SysUser user = sysUserMapper.selectById(userId);
        user.setBalance(BigDecimal.valueOf(1.00));
        sysUserMapper.updateById(user);
        quoteBoardRegistry.getOrLoad(userId).setVolRegime(VolRegime.NORMAL);
        toSettling(userId, 2);

        settlementService.settle(userId);

        assertTrue(messages(userId).stream().noneMatch(m -> m.getTitle().contains("老李停车场")),
                "a full position in a calm regime must not trigger 老李 parking warning");
    }

    @Test
    void buyingStockThatClosesAtLimitUpPostsDragonTigerMessage() {
        long userId = seedFixed(20260904L);
        insertTrade(userId, 1L, "BUY", 5);
        sealUp(userId, 1L);
        toSettling(userId, 5);

        settlementService.settle(userId);

        UserMessage message = messages(userId).stream()
                .filter(m -> m.getTitle().contains("龙虎榜"))
                .findFirst().orElse(null);
        assertNotNull(message, "a same-day limit-up BUY must draw a 龙虎榜 @player notice");
        assertEquals(1L, message.getStockId());
        assertEquals(1L, playerNotices(userId, 5).size(), "at most one @player notice per day");
    }

    @Test
    void wrongCutYesterdayThatLimitUpsTodayPostsCutTradeNotice() {
        long userId = seedFixed(20260904L);
        insertTrade(userId, 2L, "SELL", 4);
        sealUp(userId, 2L);
        toSettling(userId, 5);

        settlementService.settle(userId);

        UserMessage message = messages(userId).stream()
                .filter(m -> m.getTitle().contains("割肉"))
                .findFirst().orElse(null);
        assertNotNull(message, "a yesterday SELL that limit-ups today must draw a 割肉 @player notice");
        assertEquals(1L, playerNotices(userId, 5).size(), "at most one @player notice per day");
    }

    @Test
    void bothPlayerTriggersSameDayYieldAtMostOneNotice() {
        long userId = seedFixed(20260904L);
        insertTrade(userId, 1L, "BUY", 5);
        insertTrade(userId, 2L, "SELL", 4);
        sealUp(userId, 1L);
        sealUp(userId, 2L);
        toSettling(userId, 5);

        settlementService.settle(userId);

        long notices = playerNotices(userId, 5).stream()
                .filter(m -> m.getTitle().contains("龙虎榜") || m.getTitle().contains("割肉"))
                .count();
        assertEquals(1, notices, "only one of the two @player triggers may fire on the same day");
    }

    @Test
    void clearUserStateResetsPerDayDedup() {
        long userId = seedFixed(20260904L);
        insertTrade(userId, 1L, "BUY", 5);
        sealUp(userId, 1L);
        UserGameState gs = gameState(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        npcService.dailyAssetsJson(userId, 5, gs, board, 0.02);
        assertEquals(1, playerNotices(userId, 5).size(), "the first roll posts one @player notice");

        npcService.dailyAssetsJson(userId, 5, gs, board, 0.02);
        assertEquals(1, playerNotices(userId, 5).size(), "the same day is deduplicated into one notice");

        npcService.clearUserState(userId);
        npcService.dailyAssetsJson(userId, 5, gs, board, 0.02);
        assertEquals(2, playerNotices(userId, 5).size(),
                "clearUserState must allow a fresh @player notice on the same simulated day");
    }

    // ---- 造数 helper ----

    private long seedFixed(long worldSeed) {
        long userId = worldSeeder.seedUser("npc_" + System.nanoTime(), "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(worldSeed);
        userGameStateMapper.updateById(gs);
        return userId;
    }

    private void toSettling(long userId, int day) {
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(day);
        gs.setMarketState("SETTLING");
        userGameStateMapper.updateById(gs);
    }

    private void sealUp(long userId, long stockId) {
        quoteBoardRegistry.getOrLoad(userId).quote(stockId).setSealed(SealState.UP);
    }

    private void insertTrade(long userId, long stockId, String type, int gameDay) {
        TradeRecord record = new TradeRecord();
        record.setUserId(userId);
        record.setStockId(stockId);
        record.setTradeType(type);
        record.setPrice(BigDecimal.ONE);
        record.setShares(100);
        record.setAmount(BigDecimal.ONE);
        record.setCommission(BigDecimal.ONE);
        record.setTax(BigDecimal.ZERO);
        record.setGameDay(gameDay);
        tradeRecordMapper.insert(record);
    }

    private JsonNode snapshotJson(long userId, int gameDay) {
        UserDailySnapshot snapshot = userDailySnapshotMapper.selectOne(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .eq(UserDailySnapshot::getGameDay, gameDay));
        return snapshot == null || snapshot.getNpcAssetsJson() == null
                ? null : MAPPER.readTree(snapshot.getNpcAssetsJson());
    }

    private List<String> codesOf(JsonNode npcs) {
        return java.util.stream.IntStream.range(0, npcs.size())
                .mapToObj(i -> npcs.get(i).get("code").asString())
                .toList();
    }

    private List<UserMessage> messages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .orderByAsc(UserMessage::getId));
    }

    private List<UserMessage> playerNotices(long userId, int gameDay) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getGameDay, gameDay)
                .eq(UserMessage::getSource, "NPC")
                .eq(UserMessage::getType, "NOTIFY")
                .and(w -> w.like(UserMessage::getTitle, "龙虎榜").or().like(UserMessage::getTitle, "割肉")));
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }
}