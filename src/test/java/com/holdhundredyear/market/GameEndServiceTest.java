package com.holdhundredyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.entity.GameEvent;
import com.holdhundredyear.market.entity.LoanRecord;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.StockDailyBar;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserIpoRecord;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.entity.UserTitle;
import com.holdhundredyear.market.mapper.GameEventMapper;
import com.holdhundredyear.market.mapper.LoanRecordMapper;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserEraMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserIpoRecordMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.market.mapper.StockDailyBarMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.mapper.UserTitleMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.game.model.dto.GameResultDTO;
import com.holdhundredyear.game.model.dto.TradeDTO;
import com.holdhundredyear.trade.MarginService;
import com.holdhundredyear.trade.TradeService;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终局结算页与重开集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖 Task 7.2 / spec §12：/api/game/result 双结局汇总（收益率/指数涨幅/段位/
 * 强平流水/曲线/排行榜）与非终局拒绝、/api/game/restart 非终局拒绝、全量清表
 * + 账户与 game_state 复位 + 换种子重播种 + 行情板 evict 重建，重开后可重新开盘。
 */
@SpringBootTest
@Transactional
class GameEndServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private GameEndService gameEndService;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private TradeService tradeService;

    @Autowired
    private MarginService marginService;

    @Autowired
    private QuoteBoardRegistry quoteBoardRegistry;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private UserPositionMapper userPositionMapper;

    @Autowired
    private UserStockStateMapper userStockStateMapper;

    @Autowired
    private StockDailyBarMapper stockDailyBarMapper;

    @Autowired
    private UserDailySnapshotMapper userDailySnapshotMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Autowired
    private LoanRecordMapper loanRecordMapper;

    @Autowired
    private TradeRecordMapper tradeRecordMapper;

    @Autowired
    private UserEraMapper userEraMapper;

    @Autowired
    private UserScriptDayMapper userScriptDayMapper;

    @Autowired
    private UserIpoRecordMapper userIpoRecordMapper;

    @Autowired
    private UserTitleMapper userTitleMapper;

    @Autowired
    private GameEventMapper gameEventMapper;

    @Test
    void resultRejectsNonTerminalState() {
        long userId = seedInMarket();

        assertCode(ErrorCode.GAME_NOT_FINISHED, () -> gameEndService.result(userId),
                "a trading user has no final result yet");
    }

    @Test
    void resultBuildsFinishedSummary() {
        long userId = seedInMarket();
        insertSnapshot(userId, 1, "100200.00", "0.00", "0.00", "100200.00", 1002.0, null);
        insertSnapshot(userId, 2, "100400.00", "0.00", "0.00", "100400.00", 1004.0, null);
        insertSnapshot(userId, 3, "100600.00", "0.00", "0.00", "100600.00", 1006.0, null);
        insertSnapshot(userId, 4, "100800.00", "0.00", "0.00", "100800.00", 1008.0, null);
        insertSnapshot(userId, 5, "121000.00", "0.00", "0.00", "121000.00", 1060.0, npcJson());
        insertLoan(userId, "LIQUIDATE", "5000.00", "5000.00", 3);
        insertLoan(userId, "LIQUIDATE", "3000.00", "3000.00", 1);
        setTerminalState(userId, "FINISHED", "NORMAL_FINISH", 5, "MASTER");

        GameResultDTO result = gameEndService.result(userId);

        assertEquals("FINISHED", result.getEnding(), "ending must mirror the terminal state");
        assertEquals("NORMAL_FINISH", result.getEndReason());
        assertEquals(5, result.getEndDay());
        assertEquals(21.0, result.getTotalReturnPct(), 0.0001, "(121000 - 100000) / 100000");
        assertEquals(6.0, result.getIndexGainPct(), 0.0001, "(1060 - 1000) / 1000");
        assertEquals("MASTER", result.getFinalTier());
        assertEquals("牛散", result.getFinalTierName(), "tier name must map through SettlementService.tierNameOf");
        assertEquals("20260904", result.getWorldSeed(), "seed must come from the terminal game state, as a precision-safe string");

        List<LoanRecord> liquidations = result.getLiquidations();
        assertEquals(2, liquidations.size(), "both LIQUIDATE records belong to the payout ledger");
        assertEquals(1, liquidations.get(0).getGameDay(), "liquidations must be ordered by game day first");
        assertEquals(3, liquidations.get(1).getGameDay(), "liquidations must be ordered by game day first");

        List<GameResultDTO.CurvePoint> curve = result.getCurve();
        assertEquals(6, curve.size(), "curve must span game day 0..endDay");
        assertEquals(0, curve.get(0).getGameDay());
        assertEquals(100000.0, curve.get(0).getNetAsset(), 0.0001, "day 0 comes from the seeded snapshot");
        assertEquals(1008.0, curve.get(4).getIndexValue(), 0.0001);
        assertEquals(5, curve.get(5).getGameDay());
        assertEquals(121000.0, curve.get(5).getNetAsset(), 0.0001);

        List<GameResultDTO.RankRow> rankings = result.getRankings();
        assertEquals(9, rankings.size(), "8 NPCs plus the player");
        assertEquals(300000.0, rankings.get(0).getAsset(), 0.0001, "rankings must be sorted by asset desc");
        assertFalse(rankings.get(0).isPlayer());
        assertEquals(121000.0, rankings.get(3).getAsset(), 0.0001, "player 121000 ranks 4th of 9");
        assertEquals("您", rankings.get(3).getName());
        assertEquals("ACTIVE", rankings.get(3).getStatus(), "a finished player must stay ACTIVE");
        assertTrue(rankings.get(3).isPlayer());
        assertEquals(5000.0, rankings.get(8).getAsset(), 0.0001);
    }

    @Test
    void resultMarksBankruptPlayerRow() {
        long userId = seedInMarket();
        insertSnapshot(userId, 1, "1000.00", "0.00", "99000.00", "1000.00", 1000.0, null);
        setTerminalState(userId, "BANKRUPT", "BANKRUPT", 1, "RETAIL");

        GameResultDTO result = gameEndService.result(userId);

        assertEquals("BANKRUPT", result.getEnding());
        assertEquals("BANKRUPT", result.getEndReason());
        assertEquals(1, result.getEndDay());
        assertEquals(-99.0, result.getTotalReturnPct(), 0.0001, "(1000 - 100000) / 100000");
        assertEquals(0.0, result.getIndexGainPct(), 0.0001, "index stayed at 1000");
        assertEquals("RETAIL", result.getFinalTier());
        assertEquals("散户", result.getFinalTierName());
        assertTrue(result.getLiquidations().isEmpty(), "no liquidation records were written");
        assertEquals(2, result.getCurve().size(), "day 0 plus the bankruptcy day");

        List<GameResultDTO.RankRow> rankings = result.getRankings();
        assertEquals(1, rankings.size(), "the day-1 snapshot has no npc_assets_json, so only the player row");
        assertEquals("您", rankings.get(0).getName());
        assertEquals(1000.0, rankings.get(0).getAsset(), 0.0001);
        assertEquals("BANKRUPT", rankings.get(0).getStatus(), "a bankrupt player must be marked on the board");
        assertTrue(rankings.get(0).isPlayer());
    }

    @Test
    void resultFallsBackToLastSettledDayWhenBankruptcyStruckIntraday() {
        // 盘中爆仓（MarginService.intradayCheck 短路）当天不会走收盘结算，也就没有 endDay 当天的
        // 快照：曾经这里按 gameDay == endDay 取到 null，终局接口 500，玩家永远看不到「天台见」。
        long userId = seedInMarket();
        insertSnapshot(userId, 3, "2000.00", "0.00", "98000.00", "2000.00", 980.0, null);
        setTerminalState(userId, "BANKRUPT", "BANKRUPT", 4, "RETAIL");

        GameResultDTO result = gameEndService.result(userId);

        assertEquals("BANKRUPT", result.getEnding());
        assertEquals(4, result.getEndDay(), "the day the margin blew up stays the reported end day");
        assertEquals(-98.0, result.getTotalReturnPct(), 0.0001, "read from the last settled day: (2000 - 100000) / 100000");
        assertEquals(-2.0, result.getIndexGainPct(), 0.0001, "index 980 against the 1000 base");
        assertEquals(2, result.getCurve().size(), "day 0 plus day 3 only, day 4 never settled");
        assertEquals(1, result.getRankings().size());
        assertEquals(2000.0, result.getRankings().get(0).getAsset(), 0.0001);
        assertEquals("BANKRUPT", result.getRankings().get(0).getStatus());
    }

    @Test
    void resultSurvivesBankruptcyBeforeTheFirstClose() {
        // 第 1 天盘中就爆仓：唯一的快照是播种时的第 0 天，终局页仍要能画出来。
        long userId = seedInMarket();
        setTerminalState(userId, "BANKRUPT", "BANKRUPT", 1, "RETAIL");

        GameResultDTO result = gameEndService.result(userId);

        assertEquals("BANKRUPT", result.getEnding());
        assertEquals(0.0, result.getTotalReturnPct(), 0.0001, "the day-0 snapshot is 100000 by definition");
        assertEquals(0.0, result.getIndexGainPct(), 0.0001);
        assertEquals(1, result.getCurve().size(), "only the seeding snapshot exists");
    }

    @Test
    void restartRejectsNonTerminalState() {
        long userId = seedInMarket();

        assertCode(ErrorCode.GAME_NOT_FINISHED, () -> gameEndService.restart(userId),
                "restart must refuse a still-running game");
        assertEquals(24, stockStates(userId).size(), "a rejected restart must not wipe stock states");
        assertEquals(1, snapshots(userId).size(), "a rejected restart must not wipe snapshots");
        assertEquals(0, asDecimal("100000.00").compareTo(balance(userId)),
                "a rejected restart must not touch the account");
    }

    @Test
    void restartWipesAndReseedsWorld() {
        long userId = seedInMarket();
        tradeService.buy(userId, order(1L, 100));
        marginService.borrow(userId, asDecimal("50000.00"));
        insertEvent(userId);
        insertTitle(userId);
        insertIpo(userId);
        assertFalse(messages(userId).isEmpty(), "precondition: startDay must have left the era notice");
        setTerminalState(userId, "FINISHED", "NORMAL_FINISH", 1, "RETAIL");
        long oldSeed = gameState(userId).getWorldSeed();
        Quote stale = quote(userId, 1L);
        stale.setCurrent(999.99);

        gameEndService.restart(userId);

        UserGameState gs = gameState(userId);
        assertEquals(1, gs.getCurrentDay(), "current day must reset to 1");
        assertEquals("NOT_STARTED", gs.getMarketState());
        assertEquals(0, gs.getTickIndex());
        assertEquals(1000.0, gs.getIndexValue(), 0.0001);
        assertNull(gs.getPrevTotalAsset(), "prevTotalAsset must be explicitly nulled");
        assertEquals("NORMAL", gs.getVolRegime());
        assertEquals("RETAIL", gs.getTier());
        assertNull(gs.getEndReason(), "endReason must be explicitly nulled");
        assertNull(gs.getEndDay(), "endDay must be explicitly nulled");
        assertNotEquals(oldSeed, gs.getWorldSeed().longValue(), "restart must roll a fresh world seed");

        assertEquals(0, asDecimal("100000.00").compareTo(balance(userId)),
                "the account must reset to the initial balance");
        assertEquals(0, asDecimal("0.00").compareTo(sysUser(userId).getDebt()),
                "the debt must reset to zero");

        assertTrue(positions(userId).isEmpty(), "positions must be wiped");
        assertTrue(records(userId).isEmpty(), "trade records must be wiped");
        assertTrue(loans(userId).isEmpty(), "loan records must be wiped");
        assertTrue(messages(userId).isEmpty(), "messages must be wiped");
        assertTrue(events(userId).isEmpty(), "game events must be wiped");
        assertTrue(titles(userId).isEmpty(), "titles must be wiped");
        assertTrue(ipos(userId).isEmpty(), "ipo records must be wiped");

        List<UserDailySnapshot> snapshots = snapshots(userId);
        assertEquals(1, snapshots.size(), "only the day-0 snapshot must remain after reseeding");
        assertEquals(0, snapshots.get(0).getGameDay());
        assertEquals(0, asDecimal("100000.00").compareTo(snapshots.get(0).getNetAsset()));

        List<UserStockState> states = stockStates(userId);
        assertEquals(24, states.size(), "24 stocks must be reseeded");
        UserStockState stock1 = states.stream()
                .filter(state -> state.getStockId() == 1L).findFirst().orElse(null);
        assertNotNull(stock1);
        assertEquals(0, asDecimal("168.00").compareTo(stock1.getCurrentPrice()),
                "reseeded stock states must sit at base price");
        assertEquals(0, asDecimal("168.00").compareTo(stock1.getPrevClose()));

        assertEquals(11, eras(userId).size(), "11 eras must be reseeded");
        assertFalse(scriptDays(userId).isEmpty(), "script days must be reseeded");

        Quote fresh = quote(userId, 1L);
        assertEquals(168.0, fresh.getCurrent(), 0.0001,
                "the poisoned cached board must be evicted and rebuilt from reseeded states");
    }

    @Test
    void restartClearsDailyBarsSoTheNewRunDoesNotInheritHistory() {
        long userId = worldSeeder.seedUser("end_kline1", "$2a$10$bcrypthash");
        StockDailyBar bar = new StockDailyBar();
        bar.setUserId(userId);
        bar.setStockId(1L);
        bar.setGameDay(1);
        bar.setOpen(new BigDecimal("168.00"));
        bar.setHigh(new BigDecimal("168.00"));
        bar.setLow(new BigDecimal("168.00"));
        bar.setClose(new BigDecimal("168.00"));
        bar.setChangePct(0.0);
        stockDailyBarMapper.insert(bar);
        assertEquals(1L, dailyBars(userId), "precondition: one daily bar exists");

        gameEndService.restart(userId, GameMode.STANDARD);

        assertEquals(0L, dailyBars(userId),
                "restart must wipe stock_daily_bar, otherwise the new run opens with the old K-lines");
    }

    @Test
    void modeIsChosenPerRunAndReseedsTheSchedule() {
        long userId = worldSeeder.seedUser("end_mode1", "$2a$10$bcrypthash");
        assertEquals("STANDARD", gameState(userId).getGameMode(), "registration defaults to standard");

        // 还没开过局 → 允许按快速模式重播种：模式一局一选，不在注册那一刻绑死
        gameEndService.restart(userId, GameMode.QUICK);

        assertEquals("QUICK", gameState(userId).getGameMode(), "mode must be switchable before the first open");
        int lastEraDay = userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                        .eq(UserEra::getUserId, userId)).stream()
                .mapToInt(UserEra::getEndDay).max().orElseThrow();
        assertEquals(36, lastEraDay, "era schedule must be re-rolled for the quick-mode horizon");
    }

    @Test
    void restartIsStillRefusedOnceThisRunHasStarted() {
        long userId = worldSeeder.seedUser("end_mode2", "$2a$10$bcrypthash");
        marketStateService.startDay(userId);

        assertThrows(BizException.class, () -> gameEndService.restart(userId, GameMode.QUICK),
                "a run already underway cannot be re-rolled just to change the mode");
    }

    private long dailyBars(long userId) {
        Long count = stockDailyBarMapper.selectCount(new LambdaQueryWrapper<StockDailyBar>()
                .eq(StockDailyBar::getUserId, userId));
        return count == null ? 0L : count;
    }

    @Test
    void restartThenStartDayResumesTrading() {
        long userId = seedInMarket();
        setTerminalState(userId, "FINISHED", "NORMAL_FINISH", 1, "RETAIL");

        gameEndService.restart(userId);
        marketStateService.startDay(userId);

        UserGameState gs = gameState(userId);
        assertEquals("MORNING_OPEN", gs.getMarketState(), "a fresh world must open day 1 for trading");
        assertEquals(1, gs.getCurrentDay());
    }

    // ---- 造数 helper ----

    private long seedInMarket() {
        long userId = worldSeeder.seedUser("game_end_" + System.nanoTime(), "$2a$10$bcrypthash");
        setWorldSeed(userId, 20260904L);
        marketStateService.startDay(userId);
        return userId;
    }

    private void setWorldSeed(long userId, long worldSeed) {
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(worldSeed);
        userGameStateMapper.updateById(gs);
    }

    private void setTerminalState(long userId, String marketState, String endReason, int endDay, String tier) {
        UserGameState gs = gameState(userId);
        gs.setMarketState(marketState);
        gs.setEndReason(endReason);
        gs.setEndDay(endDay);
        gs.setTier(tier);
        userGameStateMapper.updateById(gs);
    }

    private void insertSnapshot(long userId, int gameDay, String cash, String marketValue, String debt,
                                String netAsset, double indexValue, String npcAssetsJson) {
        UserDailySnapshot snapshot = new UserDailySnapshot();
        snapshot.setUserId(userId);
        snapshot.setGameDay(gameDay);
        snapshot.setCash(new BigDecimal(cash));
        snapshot.setMarketValue(new BigDecimal(marketValue));
        snapshot.setDebt(new BigDecimal(debt));
        snapshot.setNetAsset(new BigDecimal(netAsset));
        snapshot.setIndexValue(indexValue);
        snapshot.setNpcAssetsJson(npcAssetsJson);
        userDailySnapshotMapper.insert(snapshot);
    }

    private void insertLoan(long userId, String loanType, String amount, String debtAfter, int gameDay) {
        LoanRecord record = new LoanRecord();
        record.setUserId(userId);
        record.setLoanType(loanType);
        record.setAmount(new BigDecimal(amount));
        record.setDebtAfter(new BigDecimal(debtAfter));
        record.setGameDay(gameDay);
        loanRecordMapper.insert(record);
    }

    private void insertEvent(long userId) {
        GameEvent event = new GameEvent();
        event.setUserId(userId);
        event.setScope("MARKET");
        event.setScriptType("NONE");
        event.setEventType("NOTIFY");
        event.setTitle("test event");
        event.setGameDay(1);
        gameEventMapper.insert(event);
    }

    private void insertTitle(long userId) {
        UserTitle title = new UserTitle();
        title.setUserId(userId);
        title.setTitleCode("FIRST_TRADE");
        title.setTitleName("初入江湖");
        title.setKind("GAME");
        title.setGameDay(1);
        userTitleMapper.insert(title);
    }

    private void insertIpo(long userId) {
        UserIpoRecord ipo = new UserIpoRecord();
        ipo.setUserId(userId);
        ipo.setScriptDayId(0L);
        ipo.setIpoName("测试新股");
        ipo.setIssuePrice(new BigDecimal("10.00"));
        ipo.setStatus("PENDING");
        ipo.setGameDay(1);
        userIpoRecordMapper.insert(ipo);
    }

    /** 8 个 NPC 的资产 JSON：玩家 121000 恰好排第 4。 */
    private static String npcJson() {
        double[] assets = {300000.0, 200000.0, 150000.0, 110000.0, 99000.0, 50000.0, 10000.0, 5000.0};
        List<Map<String, Object>> npcs = new ArrayList<>();
        for (int i = 0; i < assets.length; i++) {
            Map<String, Object> npc = new LinkedHashMap<>();
            npc.put("code", "NPC" + (i + 1));
            npc.put("name", "NPC" + (i + 1));
            npc.put("asset", assets[i]);
            npc.put("status", "ACTIVE");
            npcs.add(npc);
        }
        return MAPPER.writeValueAsString(npcs);
    }

    private TradeDTO order(long stockId, int shares) {
        TradeDTO dto = new TradeDTO();
        dto.setStockId(stockId);
        dto.setShares(shares);
        return dto;
    }

    /** 改价 helper：与世界锁互斥后取内存行情（锁协议与被测服务一致）。 */
    private Quote quote(long userId, long stockId) {
        ReentrantLock lock = quoteBoardRegistry.worldLock(userId);
        lock.lock();
        try {
            return quoteBoardRegistry.getOrLoad(userId).quote(stockId);
        } finally {
            lock.unlock();
        }
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private BigDecimal balance(long userId) {
        return sysUser(userId).getBalance();
    }

    private SysUser sysUser(long userId) {
        return sysUserMapper.selectById(userId);
    }

    private List<UserPosition> positions(long userId) {
        return userPositionMapper.selectList(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId));
    }

    private List<UserStockState> stockStates(long userId) {
        return userStockStateMapper.selectList(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId));
    }

    private List<UserDailySnapshot> snapshots(long userId) {
        return userDailySnapshotMapper.selectList(new LambdaQueryWrapper<UserDailySnapshot>()
                .eq(UserDailySnapshot::getUserId, userId));
    }

    private List<UserMessage> messages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
    }

    private List<LoanRecord> loans(long userId) {
        return loanRecordMapper.selectList(new LambdaQueryWrapper<LoanRecord>()
                .eq(LoanRecord::getUserId, userId));
    }

    private List<TradeRecord> records(long userId) {
        return tradeRecordMapper.selectList(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId));
    }

    private List<UserEra> eras(long userId) {
        return userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                .eq(UserEra::getUserId, userId));
    }

    private List<UserScriptDay> scriptDays(long userId) {
        return userScriptDayMapper.selectList(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId));
    }

    private List<UserIpoRecord> ipos(long userId) {
        return userIpoRecordMapper.selectList(new LambdaQueryWrapper<UserIpoRecord>()
                .eq(UserIpoRecord::getUserId, userId));
    }

    private List<UserTitle> titles(long userId) {
        return userTitleMapper.selectList(new LambdaQueryWrapper<UserTitle>()
                .eq(UserTitle::getUserId, userId));
    }

    private List<GameEvent> events(long userId) {
        return gameEventMapper.selectList(new LambdaQueryWrapper<GameEvent>()
                .eq(GameEvent::getUserId, userId));
    }

    private static BigDecimal asDecimal(String value) {
        return new BigDecimal(value);
    }

    private void assertCode(ErrorCode expected, Runnable action, String message) {
        BizException ex = assertThrows(BizException.class, action::run, message);
        assertEquals(expected, ex.getErrorCode(), message);
    }
}
