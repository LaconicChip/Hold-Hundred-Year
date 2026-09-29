package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.market.entity.LoanRecord;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.StockDailyBar;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserPosition;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.LoanRecordMapper;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.StockDailyBarMapper;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserPositionMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import com.holdhunderdyear.game.constants.GameConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收盘结算集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖 spec §3 结算 12 步在 SettlementService.settle 的落地：
 * prev_close 双写（DB+内存）、T+1 解冻、连板统计与三连板停牌核查、融资计息与追保警告、
 * 日收益评价与每日快照落库（首日基准）、段位升降档、收盘/收官状态落位、OHL 重置。
 */
@SpringBootTest
@Transactional
class SettlementServiceTest {

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private QuoteBoardRegistry quoteBoardRegistry;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserStockStateMapper userStockStateMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private UserPositionMapper userPositionMapper;

    @Autowired
    private UserDailySnapshotMapper userDailySnapshotMapper;

    @Autowired
    private StockDailyBarMapper stockDailyBarMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Autowired
    private LoanRecordMapper loanRecordMapper;

    @Test
    void settleWritesPrevCloseToDbAndMemory() {
        long userId = worldSeeder.seedUser("st_prev1", "$2a$10$bcrypthash");
        toSettling(userId, 3, 1000.0, null, null);
        setStock(userId, 1L, "168.00", "170.00", null, null, null, null, null);
        setStock(userId, 2L, "88.00", "90.50", null, null, null, null, null);
        QuoteBoard board = reloadBoard(userId);

        settlementService.settle(userId);

        assertEquals(0, stockState(userId, 1L).getPrevClose().compareTo(new BigDecimal("170.00")));
        assertEquals(0, stockState(userId, 2L).getPrevClose().compareTo(new BigDecimal("90.50")));
        assertEquals(170.00, board.quote(1L).getPrevClose(), 1e-9);
        assertEquals(90.50, board.quote(2L).getPrevClose(), 1e-9);
    }

    @Test
    void settleUnfreezesT1Positions() {
        long userId = worldSeeder.seedUser("st_t1", "$2a$10$bcrypthash");
        toSettling(userId, 1, 1000.0, null, null);
        insertPosition(userId, 1L, 200, 100, "150.00");
        insertPosition(userId, 3L, 100, 0, "120.00");

        settlementService.settle(userId);

        List<UserPosition> positions = userPositionMapper.selectList(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId)
                .orderByAsc(UserPosition::getStockId));
        assertEquals(2, positions.size());
        assertEquals(200, positions.get(0).getAvailableShares());
        assertEquals(200, positions.get(0).getShares());
        assertEquals(100, positions.get(1).getAvailableShares());
        assertEquals(100, positions.get(1).getShares());
    }

    @Test
    void settleUpdatesLimitUpStreakByClosingPrice() {
        long userId = worldSeeder.seedUser("st_streak1", "$2a$10$bcrypthash");
        toSettling(userId, 3, 1000.0, null, null);
        setStock(userId, 1L, "168.00", "184.80", 1, false, null, null, null);
        setStock(userId, 2L, "88.00", "90.50", 4, false, null, null, null);
        setStock(userId, 3L, "120.00", "121.00", 2, true, null, null, null);
        QuoteBoard board = reloadBoard(userId);

        settlementService.settle(userId);

        assertEquals(2, stockState(userId, 1L).getLimitUpStreak(), "收涨停连板 +1");
        assertEquals(0, stockState(userId, 2L).getLimitUpStreak(), "未收涨停归 0");
        assertEquals(2, stockState(userId, 3L).getLimitUpStreak(), "停牌股连板不动");
        assertEquals(2, board.quote(1L).getLimitUpStreak());
        assertEquals(0, board.quote(2L).getLimitUpStreak());
    }

    @Test
    void settleHaltsStockAfterThreeConsecutiveLimitUps() {
        long userId = worldSeeder.seedUser("st_halt1", "$2a$10$bcrypthash");
        toSettling(userId, 4, 1000.0, null, null);
        setStock(userId, 1L, "168.00", "184.80", 2, false, null, null, null);
        setStock(userId, 2L, "88.00", "90.50", 2, false, null, null, null);
        QuoteBoard board = reloadBoard(userId);

        settlementService.settle(userId);

        UserStockState halted = stockState(userId, 1L);
        assertEquals(3, halted.getLimitUpStreak());
        assertTrue(halted.getHalted());
        assertEquals(6, halted.getHaltUntilDay(), "停牌写 day+2：N+1 全天停牌，N+2 复牌");
        assertTrue(board.quote(1L).isHalted(), "内存板同步置停牌");
        assertFalse(board.quote(2L).isHalted(), "未触发三连板的股票内存板不得停牌");
        assertFalse(stockState(userId, 2L).getHalted(), "未触发三连板的股票 DB 不得停牌");

        UserMessage notice = messages(userId).stream()
                .filter(m -> "停牌核查".equals(m.getTitle()))
                .findFirst().orElse(null);
        assertNotNull(notice);
        assertEquals(1L, notice.getStockId());
        assertTrue(notice.getContent().contains(StockDefs.byId(1L).name()));
    }

    @Test
    void settleChargesDailyInterestOnPositiveDebt() {
        long userId = worldSeeder.seedUser("st_int1", "$2a$10$bcrypthash");
        toSettling(userId, 2, 1000.0, null, null);
        setUserDebt(userId, "10000.00");

        settlementService.settle(userId);

        // 日息 = 10000 × 8% / DAYS_PER_YEAR(24) = 33.33（年化不变，一年交易日变少则单日计提更多）
        assertEquals(0, sysUser(userId).getDebt().compareTo(new BigDecimal("10033.33")));
        List<LoanRecord> records = loans(userId);
        assertEquals(1, records.size());
        LoanRecord record = records.get(0);
        assertEquals("INTEREST", record.getLoanType());
        assertEquals(0, record.getAmount().compareTo(new BigDecimal("33.33")));
        assertEquals(0, record.getDebtAfter().compareTo(new BigDecimal("10033.33")));
        assertEquals(2, record.getGameDay());
        assertTrue(messages(userId).stream().noneMatch(m -> "追保通知".equals(m.getTitle())),
                "担保比例充足时不得发追保警告");
    }

    @Test
    void settleSkipsInterestWhenDebtZero() {
        long userId = worldSeeder.seedUser("st_int0", "$2a$10$bcrypthash");
        toSettling(userId, 1, 1000.0, null, null);

        settlementService.settle(userId);

        assertEquals(0, sysUser(userId).getDebt().compareTo(BigDecimal.ZERO));
        assertTrue(loans(userId).isEmpty());
    }

    @Test
    void settleSendsMarginCallNoticeWhenRatioBelowLine() {
        long userId = worldSeeder.seedUser("st_margin1", "$2a$10$bcrypthash");
        toSettling(userId, 2, 1000.0, null, null);
        setUserDebt(userId, "80000.00");

        settlementService.settle(userId);

        UserMessage notice = messages(userId).stream()
                .filter(m -> "追保通知".equals(m.getTitle()))
                .findFirst().orElse(null);
        assertNotNull(notice, "担保比例 (100000/80177.78 约 1.25) 低于 1.4 必须发追保警告");
    }

    @Test
    void settleWritesDailySnapshotOnFirstDay() {
        long userId = worldSeeder.seedUser("st_snap1", "$2a$10$bcrypthash");
        toSettling(userId, 1, 1050.0, null, null);
        setStock(userId, 1L, "168.00", "184.80", null, null, null, null, null);
        insertPosition(userId, 1L, 200, 0, "150.00");
        setUserDebt(userId, "10000.00");

        settlementService.settle(userId);

        UserDailySnapshot snapshot = snapshot(userId, 1);
        assertNotNull(snapshot);
        assertEquals(0, snapshot.getCash().compareTo(new BigDecimal("100000.00")));
        assertEquals(0, snapshot.getMarketValue().compareTo(new BigDecimal("36960.00")));
        assertEquals(0, snapshot.getDebt().compareTo(new BigDecimal("10033.33")));
        assertEquals(0, snapshot.getNetAsset().compareTo(new BigDecimal("126926.67")));
        assertEquals(26.92667, snapshot.getDayReturnPct(), 1e-6);
        assertEquals(1050.0, snapshot.getIndexValue(), 1e-9);
        assertEquals(5.0, snapshot.getIndexDayPct(), 1e-6, "昨收指数取前日快照（day-0 播种 1000）");
        assertEquals(0.0, stockState(userId, 1L).getGapPct(), 1e-9,
                "结算不掷盘后消息，gap_pct 必须保持 0");
        assertNotNull(snapshot.getNpcAssetsJson(), "Task 8.4 fills the NPC assets json at settlement");
        assertEquals(0, gameState(userId).getPrevTotalAsset().compareTo(new BigDecimal("126926.67")),
                "结算尾部 prevTotalAsset 回写本次净资产");
        UserMessage review = messages(userId).stream()
                .filter(m -> "日收益评价".equals(m.getTitle()))
                .findFirst().orElse(null);
        assertNotNull(review);
        assertTrue(review.getContent().contains("26.93"));
        assertTrue(review.getContent().contains("跑赢"));
    }

    @Test
    void settleUsesPreviousDaySnapshotForIndexDayPct() {
        long userId = worldSeeder.seedUser("st_snap2", "$2a$10$bcrypthash");
        toSettling(userId, 5, 1052.5, null, new BigDecimal("95000.00"));
        insertSnapshot(userId, 4, 1002.5);
        setUserBalance(userId, "90000.00");

        settlementService.settle(userId);

        UserDailySnapshot snapshot = snapshot(userId, 5);
        assertNotNull(snapshot);
        assertEquals(-5.263158, snapshot.getDayReturnPct(), 1e-6);
        assertEquals(4.987531, snapshot.getIndexDayPct(), 1e-6, "有前日快照按前日 indexValue 基准");
        UserMessage review = messages(userId).stream()
                .filter(m -> "日收益评价".equals(m.getTitle()))
                .findFirst().orElse(null);
        assertNotNull(review);
        assertTrue(review.getContent().contains("跑输"), "日收益 -5.26% 对大盘 +4.99% 必须判跑输");
    }

    @Test
    void settleFallsBackToInitialIndexWhenNoPrevSnapshot() {
        long userId = worldSeeder.seedUser("st_snap0", "$2a$10$bcrypthash");
        toSettling(userId, 1, 1100.0, null, null);
        userDailySnapshotMapper.delete(new LambdaQueryWrapper<UserDailySnapshot>()
                .eq(UserDailySnapshot::getUserId, userId));

        settlementService.settle(userId);

        UserDailySnapshot snapshot = snapshot(userId, 1);
        assertNotNull(snapshot);
        assertEquals(10.0, snapshot.getIndexDayPct(), 1e-6, "无前日快照按 INITIAL_INDEX 基期兜底");
    }

    @Test
    void settlePromotesTierAndNotifies() {
        long userId = worldSeeder.seedUser("st_tier1", "$2a$10$bcrypthash");
        toSettling(userId, 2, 1000.0, null, null);
        setUserBalance(userId, "160000.00");

        settlementService.settle(userId);

        assertEquals("MIDDLE", gameState(userId).getTier());
        UserMessage promote = messages(userId).stream()
                .filter(m -> m.getTitle().contains("段位晋升"))
                .findFirst().orElse(null);
        assertNotNull(promote);
        assertTrue(promote.getContent().contains("递烟"));
    }

    @Test
    void settleDemotesTierAndNotifies() {
        long userId = worldSeeder.seedUser("st_tier2", "$2a$10$bcrypthash");
        toSettling(userId, 2, 1000.0, "MIDDLE", new BigDecimal("100000.00"));

        settlementService.settle(userId);

        assertEquals("RETAIL", gameState(userId).getTier());
        UserMessage demote = messages(userId).stream()
                .filter(m -> m.getTitle().contains("段位回落"))
                .findFirst().orElse(null);
        assertNotNull(demote);
        assertTrue(demote.getContent().contains("张大妈"));
    }

    @Test
    void settleKeepsTierSilentWhenUnchanged() {
        long userId = worldSeeder.seedUser("st_tier3", "$2a$10$bcrypthash");
        toSettling(userId, 2, 1000.0, null, null);

        settlementService.settle(userId);

        assertEquals("RETAIL", gameState(userId).getTier());
        assertTrue(messages(userId).stream().noneMatch(m -> m.getTitle().contains("段位")));
    }

    @Test
    void settleClosesRegularDayAndPostsSettlementNotice() {
        long userId = worldSeeder.seedUser("st_close1", "$2a$10$bcrypthash");
        toSettling(userId, 3, 1005.0, null, null);

        settlementService.settle(userId);

        UserGameState after = gameState(userId);
        assertEquals("CLOSED", after.getMarketState());
        assertNull(after.getEndReason());
        assertNull(after.getEndDay());
        assertTrue(messages(userId).stream().anyMatch(m -> "收盘结算".equals(m.getTitle())));
    }

    @Test
    void settleFinishesGameAtFinalDay() {
        long userId = worldSeeder.seedUser("st_fin1", "$2a$10$bcrypthash");
        toSettling(userId, GameConstants.TOTAL_DAYS, 1100.0, null, null);

        settlementService.settle(userId);

        UserGameState after = gameState(userId);
        assertEquals("FINISHED", after.getMarketState());
        assertEquals("NORMAL_FINISH", after.getEndReason());
        assertEquals(GameConstants.TOTAL_DAYS, after.getEndDay());
        assertTrue(messages(userId).stream().noneMatch(m -> "收盘结算".equals(m.getTitle())));
    }

    @Test
    void settleResetsOhlForTradingStocksOnly() {
        long userId = worldSeeder.seedUser("st_ohl1", "$2a$10$bcrypthash");
        toSettling(userId, 3, 1000.0, null, null);
        setStock(userId, 1L, "168.00", "170.00", null, null, "169.00", "172.00", "167.50");
        setStock(userId, 3L, "120.00", "121.00", null, true, "121.00", "125.00", "119.00");
        QuoteBoard board = reloadBoard(userId);

        settlementService.settle(userId);

        UserStockState trading = stockState(userId, 1L);
        assertEquals(0, trading.getOpenPrice().compareTo(new BigDecimal("170.00")));
        assertEquals(0, trading.getHighPrice().compareTo(new BigDecimal("170.00")));
        assertEquals(0, trading.getLowPrice().compareTo(new BigDecimal("170.00")));
        assertEquals(170.0, board.quote(1L).getOpen(), 1e-9);
        assertEquals(170.0, board.quote(1L).getHigh(), 1e-9);
        assertEquals(170.0, board.quote(1L).getLow(), 1e-9);
        UserStockState halted = stockState(userId, 3L);
        assertEquals(0, halted.getOpenPrice().compareTo(new BigDecimal("121.00")));
        assertEquals(0, halted.getHighPrice().compareTo(new BigDecimal("125.00")));
        assertEquals(0, halted.getLowPrice().compareTo(new BigDecimal("119.00")));
        assertEquals(121.0, board.quote(3L).getOpen(), 1e-9);
        assertEquals(125.0, board.quote(3L).getHigh(), 1e-9);
        assertEquals(119.0, board.quote(3L).getLow(), 1e-9);
    }

    @Test
    void settlePersistsDayChangePctForClosedDisplay() {
        long userId = worldSeeder.seedUser("st_daypct1", "$2a$10$bcrypthash");
        toSettling(userId, 3, 1000.0, null, null);
        setStock(userId, 1L, "168.00", "170.00", null, null, null, null, null);
        setStock(userId, 2L, "88.00", "84.00", null, null, null, null, null);
        QuoteBoard board = reloadBoard(userId);

        settlementService.settle(userId);

        double upPct = (170.00 - 168.00) / 168.00;
        double downPct = (84.00 - 88.00) / 88.00;
        assertEquals(upPct, stockState(userId, 1L).getDayChangePct(), 1e-9,
                "结算必须把当日涨跌幅写入 day_change_pct（比率口径）");
        assertEquals(downPct, stockState(userId, 2L).getDayChangePct(), 1e-9);
        assertEquals(upPct, board.quote(1L).getLastClosePct(), 1e-9,
                "内存板 Quote.lastClosePct 同步当日涨跌幅");
        assertEquals(0.0, board.quote(1L).dayChangePct(), 1e-9,
                "prev_close 双写后 dayChangePct() 恒为 0——收盘涨跌幅必须走 lastClosePct 通道");
    }

    @Test
    void settleWritesDailyBarBeforeOhlReset() {
        long userId = worldSeeder.seedUser("st_bar1", "$2a$10$bcrypthash");
        toSettling(userId, 3, 1000.0, null, null);
        setStock(userId, 1L, "168.00", "170.00", null, false, "169.00", "172.00", "167.50");
        // 停牌冻结股：价格冻结自停牌日起 prevClose==current（生产不变式），OHL 保留停牌前值
        setStock(userId, 3L, "121.00", "121.00", null, true, "121.00", "125.00", "119.00");
        QuoteBoard board = reloadBoard(userId);

        settlementService.settle(userId);

        StockDailyBar bar1 = dailyBar(userId, 1L, 3);
        assertNotNull(bar1, "正常股必须落日K bar");
        assertEquals(0, bar1.getOpen().compareTo(new BigDecimal("169.00")));
        assertEquals(0, bar1.getHigh().compareTo(new BigDecimal("172.00")));
        assertEquals(0, bar1.getLow().compareTo(new BigDecimal("167.50")));
        assertEquals(0, bar1.getClose().compareTo(new BigDecimal("170.00")));
        assertEquals((170.00 - 168.00) / 168.00, bar1.getChangePct(), 1e-9,
                "bar 涨跌幅为当日日内涨跌幅（比率口径）");
        // bar 必须在 OHL 重置之前落库：bar 保存当日日内 OHL，state 的 OHL 已重置为收盘价
        assertEquals(0, stockState(userId, 1L).getOpenPrice().compareTo(new BigDecimal("170.00")));

        StockDailyBar bar3 = dailyBar(userId, 3L, 3);
        assertNotNull(bar3, "停牌股也要落 bar（价格冻结）");
        assertEquals(0, bar3.getOpen().compareTo(new BigDecimal("121.00")));
        assertEquals(0, bar3.getHigh().compareTo(new BigDecimal("125.00")));
        assertEquals(0, bar3.getLow().compareTo(new BigDecimal("119.00")));
        assertEquals(0, bar3.getClose().compareTo(new BigDecimal("121.00")));
        assertEquals(0.0, bar3.getChangePct(), 1e-9, "停牌股价格冻结、涨跌幅 0");
    }

    // ---- 造数 helper ----

    /** 置为待结算上下文：交易日、收盘指数、段位与昨日净资产（null=保持播种值）。 */
    private void toSettling(long userId, int day, double closeIndex, String tier, BigDecimal prevTotalAsset) {
        UserGameState state = gameState(userId);
        state.setCurrentDay(day);
        state.setMarketState("SETTLING");
        state.setIndexValue(closeIndex);
        if (tier != null) {
            state.setTier(tier);
        }
        state.setPrevTotalAsset(prevTotalAsset);
        userGameStateMapper.updateById(state);
    }

    /** 调整个股收盘上下文（null=保持播种值），随后由 reloadBoard 重建内存板。 */
    private void setStock(long userId, long stockId, String prevClose, String current,
                          Integer streak, Boolean halted, String open, String high, String low) {
        UserStockState state = stockState(userId, stockId);
        if (prevClose != null) {
            state.setPrevClose(new BigDecimal(prevClose));
        }
        if (current != null) {
            state.setCurrentPrice(new BigDecimal(current));
        }
        if (streak != null) {
            state.setLimitUpStreak(streak);
        }
        if (halted != null) {
            state.setHalted(halted);
        }
        if (open != null) {
            state.setOpenPrice(new BigDecimal(open));
        }
        if (high != null) {
            state.setHighPrice(new BigDecimal(high));
        }
        if (low != null) {
            state.setLowPrice(new BigDecimal(low));
        }
        userStockStateMapper.updateById(state);
    }

    private void setUserDebt(long userId, String debt) {
        SysUser user = sysUser(userId);
        user.setDebt(new BigDecimal(debt));
        sysUserMapper.updateById(user);
    }

    private void setUserBalance(long userId, String balance) {
        SysUser user = sysUser(userId);
        user.setBalance(new BigDecimal(balance));
        sysUserMapper.updateById(user);
    }

    private void insertPosition(long userId, long stockId, int shares, int available, String avgCost) {
        UserPosition position = new UserPosition();
        position.setUserId(userId);
        position.setStockId(stockId);
        position.setShares(shares);
        position.setAvailableShares(available);
        position.setAvgCost(new BigDecimal(avgCost));
        userPositionMapper.insert(position);
    }

    private void insertSnapshot(long userId, int gameDay, double indexValue) {
        UserDailySnapshot snapshot = new UserDailySnapshot();
        snapshot.setUserId(userId);
        snapshot.setGameDay(gameDay);
        snapshot.setCash(BigDecimal.ZERO);
        snapshot.setMarketValue(BigDecimal.ZERO);
        snapshot.setDebt(BigDecimal.ZERO);
        snapshot.setNetAsset(BigDecimal.ZERO);
        snapshot.setIndexValue(indexValue);
        userDailySnapshotMapper.insert(snapshot);
    }

    /** 造数完成后从 DB 重建行情板；settle 内部 getOrLoad 复用同一实例，内存断言因此有效。 */
    private QuoteBoard reloadBoard(long userId) {
        quoteBoardRegistry.evict(userId);
        return quoteBoardRegistry.getOrLoad(userId);
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private UserStockState stockState(long userId, long stockId) {
        return userStockStateMapper.selectOne(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId)
                .eq(UserStockState::getStockId, stockId));
    }

    private SysUser sysUser(long userId) {
        return sysUserMapper.selectById(userId);
    }

    private List<UserMessage> messages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
    }

    private List<LoanRecord> loans(long userId) {
        return loanRecordMapper.selectList(new LambdaQueryWrapper<LoanRecord>()
                .eq(LoanRecord::getUserId, userId));
    }

    private UserDailySnapshot snapshot(long userId, int gameDay) {
        return userDailySnapshotMapper.selectOne(new LambdaQueryWrapper<UserDailySnapshot>()
                .eq(UserDailySnapshot::getUserId, userId)
                .eq(UserDailySnapshot::getGameDay, gameDay));
    }

    private StockDailyBar dailyBar(long userId, long stockId, int gameDay) {
        return stockDailyBarMapper.selectOne(new LambdaQueryWrapper<StockDailyBar>()
                .eq(StockDailyBar::getUserId, userId)
                .eq(StockDailyBar::getStockId, stockId)
                .eq(StockDailyBar::getGameDay, gameDay));
    }
}
