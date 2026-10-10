package com.holdhundredyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.model.dto.LoanResultDTO;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.LoanRecord;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.mapper.LoanRecordMapper;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 融资服务集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖 spec §6.4 在 MarginService 的落地：
 * 借款额度（累计负债 ≤ 净资产）与 BORROW 流水、还款校验顺序（先负债后现金）与 REPAY 流水、
 * 非正金额拒绝、终态门禁（任意市场态可借还，FINISHED/BANKRUPT 拒绝）、
 * 追保通知（只警告不动账）、强平（市值从大到小、费用口径同卖出、达标即止、
 * 跳过跌停封死）、爆仓判定（BANKRUPT + 天台通知、不再推普通强平通知）、
 * 负债为 0 短路、settleCheck 与 intradayCheck 两入口结果一致。
 */
@SpringBootTest
@Transactional
class MarginServiceTest {

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private MarginService marginService;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private QuoteBoardRegistry quoteBoardRegistry;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private UserPositionMapper userPositionMapper;

    @Autowired
    private TradeRecordMapper tradeRecordMapper;

    @Autowired
    private LoanRecordMapper loanRecordMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Test
    void borrowCreditsCashAndWritesBorrowRecord() {
        long userId = seedInMarket();

        LoanResultDTO result = marginService.borrow(userId, asDecimal("50000.00"));

        assertEquals(0, asDecimal("150000.00").compareTo(balance(userId)),
                "balance must be 100000 + 50000");
        assertEquals(0, asDecimal("50000.00").compareTo(sysUser(userId).getDebt()),
                "debt must grow by the borrowed amount");
        assertEquals(0, asDecimal("150000.00").compareTo(result.getBalance()), "receipt balance");
        assertEquals(0, asDecimal("50000.00").compareTo(result.getDebt()), "receipt debt");
        assertEquals(0, asDecimal("50000.00").compareTo(result.getBorrowable()),
                "borrowable = assets 150000 - debt 50000 - debt*1.0 = 50000");
        List<LoanRecord> loans = loans(userId);
        assertEquals(1, loans.size());
        LoanRecord record = loans.get(0);
        assertEquals("BORROW", record.getLoanType());
        assertEquals(0, asDecimal("50000.00").compareTo(record.getAmount()));
        assertEquals(0, asDecimal("50000.00").compareTo(record.getDebtAfter()));
        assertEquals(1, record.getGameDay(), "day-1 borrow must be recorded on day 1");
    }

    @Test
    void borrowAllowsExactLimitAndRejectsBeyondIt() {
        long userId = seedInMarket();

        assertCode(ErrorCode.MARGIN_EXCEED, () -> marginService.borrow(userId, asDecimal("100001.00")),
                "total debt may not exceed the net asset 100000");
        assertEquals(0, asDecimal("100000.00").compareTo(balance(userId)),
                "rejected borrow must not touch cash");

        marginService.borrow(userId, asDecimal("100000.00"));
        assertEquals(0, asDecimal("200000.00").compareTo(balance(userId)),
                "borrowing exactly the net asset is allowed");
        assertEquals(0, asDecimal("100000.00").compareTo(sysUser(userId).getDebt()),
                "debt must equal the net asset at the exact limit");
    }

    @Test
    void repayReducesDebtAndWritesRepayRecord() {
        long userId = seedInMarket();
        marginService.borrow(userId, asDecimal("50000.00"));

        LoanResultDTO result = marginService.repay(userId, asDecimal("20000.00"));

        assertEquals(0, asDecimal("130000.00").compareTo(balance(userId)), "150000 - 20000");
        assertEquals(0, asDecimal("30000.00").compareTo(sysUser(userId).getDebt()), "50000 - 20000");
        assertEquals(0, asDecimal("70000.00").compareTo(result.getBorrowable()),
                "borrowable = assets 130000 - debt 30000 - debt*1.0 = 70000");
        List<LoanRecord> loans = loans(userId);
        assertEquals(2, loans.size(), "one BORROW and one REPAY record");
        LoanRecord repay = loans.stream()
                .filter(record -> "REPAY".equals(record.getLoanType()))
                .findFirst().orElse(null);
        assertNotNull(repay);
        assertEquals(0, asDecimal("20000.00").compareTo(repay.getAmount()));
        assertEquals(0, asDecimal("30000.00").compareTo(repay.getDebtAfter()));
        assertEquals(1, repay.getGameDay());
    }

    @Test
    void repayBeyondDebtIsRejected() {
        long userId = seedInMarket();
        setUserDebt(userId, "50000.00");

        assertCode(ErrorCode.REPAY_EXCEED, () -> marginService.repay(userId, asDecimal("60000.00")),
                "repaying more than the outstanding debt must be rejected");
        assertEquals(0, asDecimal("50000.00").compareTo(sysUser(userId).getDebt()),
                "failed repay must not touch debt");
        assertTrue(loans(userId).isEmpty(), "failed repay must not leave a record");
    }

    @Test
    void repayBeyondCashIsRejected() {
        long userId = seedInMarket();
        setUserBalance(userId, "10000.00");
        setUserDebt(userId, "50000.00");

        assertCode(ErrorCode.INSUFFICIENT_CASH, () -> marginService.repay(userId, asDecimal("20000.00")),
                "the debt check passes but the cash check must reject");
        assertEquals(0, asDecimal("10000.00").compareTo(balance(userId)),
                "failed repay must not touch cash");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        long userId = seedInMarket();

        assertCode(ErrorCode.PARAM_ERROR, () -> marginService.borrow(userId, asDecimal("0.00")));
        assertCode(ErrorCode.PARAM_ERROR, () -> marginService.borrow(userId, asDecimal("-100.00")));
        assertCode(ErrorCode.PARAM_ERROR, () -> marginService.repay(userId, asDecimal("0.00")));
        assertTrue(loans(userId).isEmpty(), "rejected amounts must not leave records");
    }

    @Test
    void borrowAndRepayWorkOutsideTradingHoursButNotAfterGameOver() {
        long userId = seedInMarket();

        setState(userId, "CLOSED");
        marginService.borrow(userId, asDecimal("10000.00"));
        assertEquals(0, asDecimal("110000.00").compareTo(balance(userId)),
                "borrowing is allowed in any non-terminal market state");

        setState(userId, "FINISHED");
        assertCode(ErrorCode.FINISHED_STATE, () -> marginService.borrow(userId, asDecimal("10000.00")));

        setState(userId, "BANKRUPT");
        assertCode(ErrorCode.FINISHED_STATE, () -> marginService.repay(userId, asDecimal("10000.00")));
    }

    @Test
    void marginCallBelowCallLineOnlyWarns() {
        long userId = seedInMarket();
        setUserDebt(userId, "80000.00");
        String stateBefore = gameState(userId).getMarketState();

        boolean bankrupt = marginService.settleCheck(userId, gameState(userId), sysUser(userId),
                positions(userId), board(userId));

        assertFalse(bankrupt, "ratio 100000/80000 = 1.25 is below the call line but far from bankruptcy");
        UserMessage notice = messages(userId).stream()
                .filter(message -> "追保通知".equals(message.getTitle()))
                .findFirst().orElse(null);
        assertNotNull(notice, "ratio below 1.4 must push a margin call notice");
        assertTrue(loans(userId).isEmpty(), "warning-only must not write loan records");
        assertTrue(records(userId).isEmpty(), "warning-only must not sell anything");
        assertEquals(0, asDecimal("100000.00").compareTo(balance(userId)), "warning-only must not touch cash");
        assertEquals(0, asDecimal("80000.00").compareTo(sysUser(userId).getDebt()));
        assertEquals(stateBefore, gameState(userId).getMarketState(),
                "warning-only must not change the market state");
    }

    @Test
    void liquidationSellsLargestPositionFirstAndStopsAtTarget() {
        long userId = seedInMarket();
        setUserBalance(userId, "0.00");
        setUserDebt(userId, "180000.00");
        insertPosition(userId, 1L, 1000, 1000, "168.00");
        insertPosition(userId, 2L, 500, 500, "88.00");

        boolean bankrupt = marginService.settleCheck(userId, gameState(userId), sysUser(userId),
                positions(userId), board(userId));

        assertFalse(bankrupt, "after selling stock 1 the ratio is far above the 1.3 target");
        List<TradeRecord> trades = records(userId);
        assertEquals(1, trades.size(), "only the largest position must be sold");
        TradeRecord sell = trades.get(0);
        assertEquals(1L, sell.getStockId());
        assertEquals("SELL", sell.getTradeType());
        assertEquals(0, asDecimal("168.00").compareTo(sell.getPrice()));
        assertEquals(1000, sell.getShares());
        assertEquals(0, asDecimal("168000.00").compareTo(sell.getAmount()));
        assertEquals(0, asDecimal("50.40").compareTo(sell.getCommission()), "168000 * 0.0003");
        assertEquals(0, asDecimal("168.00").compareTo(sell.getTax()), "168000 * 0.001");
        assertEquals(1, sell.getGameDay());

        List<LoanRecord> loans = loans(userId);
        assertEquals(1, loans.size());
        assertEquals("LIQUIDATE", loans.get(0).getLoanType());
        assertEquals(0, asDecimal("167781.60").compareTo(loans.get(0).getAmount()),
                "168000.00 - 50.40 - 168.00 repaid immediately");
        assertEquals(0, asDecimal("12218.40").compareTo(loans.get(0).getDebtAfter()));

        assertEquals(0, asDecimal("0.00").compareTo(balance(userId)),
                "the whole net proceeds must repay the debt");
        assertEquals(0, asDecimal("12218.40").compareTo(sysUser(userId).getDebt()));
        assertNull(position(userId, 1L), "a fully liquidated position must be deleted");
        assertEquals(500, position(userId, 2L).getShares(),
                "the ratio reached the 1.3 target, so stock 2 must stay intact");

        assertNotNull(messages(userId).stream()
                .filter(message -> "追保通知".equals(message.getTitle())).findFirst().orElse(null));
        assertNotNull(messages(userId).stream()
                .filter(message -> "强制平仓".equals(message.getTitle())).findFirst().orElse(null));
    }

    @Test
    void liquidationSkipsSealedLimitDownStock() {
        long userId = seedInMarket();
        setUserBalance(userId, "0.00");
        setUserDebt(userId, "52000.00");
        insertPosition(userId, 1L, 300, 300, "168.00");
        insertPosition(userId, 2L, 100, 100, "88.00");
        Quote sealed = quote(userId, 2L);
        sealed.setCurrent(sealed.limitDownPrice());

        boolean bankrupt = marginService.intradayCheck(userId, gameState(userId), board(userId));

        assertFalse(bankrupt, "selling stock 1 must restore the ratio above the 1.3 target");
        List<TradeRecord> trades = records(userId);
        assertEquals(1, trades.size(), "the sealed limit-down stock must be skipped");
        TradeRecord sell = trades.get(0);
        assertEquals(1L, sell.getStockId());
        assertEquals(0, asDecimal("50400.00").compareTo(sell.getAmount()), "300 * 168.00");
        assertEquals(0, asDecimal("15.12").compareTo(sell.getCommission()), "50400 * 0.0003");
        assertEquals(0, asDecimal("50.40").compareTo(sell.getTax()), "50400 * 0.001");

        List<LoanRecord> loans = loans(userId);
        assertEquals(1, loans.size());
        assertEquals("LIQUIDATE", loans.get(0).getLoanType());
        assertEquals(0, asDecimal("50334.48").compareTo(loans.get(0).getAmount()),
                "50400.00 - 15.12 - 50.40 repaid immediately");
        assertEquals(0, asDecimal("1665.52").compareTo(loans.get(0).getDebtAfter()));

        assertEquals(0, asDecimal("0.00").compareTo(balance(userId)),
                "the whole net proceeds must repay the debt");
        assertEquals(0, asDecimal("1665.52").compareTo(sysUser(userId).getDebt()));
        assertEquals(100, position(userId, 2L).getShares(), "the sealed stock must not be force-sold");

        assertNotNull(messages(userId).stream()
                .filter(message -> "追保通知".equals(message.getTitle())).findFirst().orElse(null));
        assertNotNull(messages(userId).stream()
                .filter(message -> "强制平仓".equals(message.getTitle())).findFirst().orElse(null));
    }

    @Test
    void bankruptcyWhenStillInsolventAfterLiquidation() {
        long userId = seedInMarket();
        setUserBalance(userId, "0.00");
        setUserDebt(userId, "200000.00");
        insertPosition(userId, 1L, 1000, 1000, "168.00");
        insertPosition(userId, 2L, 100, 100, "88.00");
        UserGameState gs = gameState(userId);

        boolean bankrupt = marginService.settleCheck(userId, gs, sysUser(userId),
                positions(userId), board(userId));

        assertTrue(bankrupt, "the net asset after selling everything must still be negative");
        assertEquals("BANKRUPT", gs.getMarketState(), "settleCheck marks bankruptcy on the passed state");
        assertEquals("BANKRUPT", gs.getEndReason());
        assertEquals(1, gs.getEndDay());

        assertEquals(2, records(userId).size(), "both positions must be force-sold");
        TradeRecord lastSell = records(userId).stream()
                .filter(record -> record.getStockId() == 2L).findFirst().orElse(null);
        assertNotNull(lastSell);
        assertEquals(0, asDecimal("5.00").compareTo(lastSell.getCommission()),
                "8800 * 0.0003 = 2.64 must be floored at the 5.00 minimum");
        assertEquals(0, asDecimal("8.80").compareTo(lastSell.getTax()), "8800 * 0.001");
        assertEquals(2, loans(userId).size());
        assertEquals(0, asDecimal("0.00").compareTo(balance(userId)));
        assertEquals(0, asDecimal("23432.20").compareTo(sysUser(userId).getDebt()),
                "total repaid 167781.60 + 8786.20 of the 200000 debt");

        assertNotNull(messages(userId).stream()
                        .filter(message -> "爆仓".equals(message.getTitle())).findFirst().orElse(null),
                "bankruptcy must push the rooftop notice");
        assertTrue(messages(userId).stream().noneMatch(message -> "强制平仓".equals(message.getTitle())),
                "the bankrupt path must not push the plain liquidation notice");
    }

    @Test
    void debtZeroShortCircuitsAllChecks() {
        long userId = seedInMarket();
        UserGameState gs = gameState(userId);

        assertFalse(marginService.settleCheck(userId, gs, sysUser(userId), positions(userId), board(userId)),
                "a debt-free user must skip all checks");
        assertFalse(marginService.intradayCheck(userId, gs, board(userId)),
                "a debt-free user must skip all checks");

        assertTrue(messages(userId).stream()
                        .noneMatch(message -> "追保通知".equals(message.getTitle())),
                "a debt-free user must not receive margin notices");
        assertTrue(loans(userId).isEmpty());
        assertTrue(records(userId).isEmpty());
        assertEquals(gs.getMarketState(), gameState(userId).getMarketState(),
                "a debt-free user's market state must not change");
        assertNull(gs.getEndReason());
    }

    @Test
    void settleCheckAndIntradayCheckProduceIdenticalResults() {
        long settleUserId = seedInMarket();
        setUserDebt(settleUserId, "80000.00");
        long intradayUserId = seedInMarket();
        setUserDebt(intradayUserId, "80000.00");

        boolean settleBankrupt = marginService.settleCheck(settleUserId, gameState(settleUserId),
                sysUser(settleUserId), positions(settleUserId), board(settleUserId));
        boolean intradayBankrupt = marginService.intradayCheck(intradayUserId,
                gameState(intradayUserId), board(intradayUserId));

        assertFalse(settleBankrupt);
        assertFalse(intradayBankrupt);
        assertEquals(1, messages(settleUserId).stream()
                        .filter(message -> "追保通知".equals(message.getTitle())).count(),
                "the settle path must warn exactly once");
        assertEquals(1, messages(intradayUserId).stream()
                        .filter(message -> "追保通知".equals(message.getTitle())).count(),
                "the intraday path must warn exactly once");
        assertEquals(0, sysUser(settleUserId).getBalance().compareTo(sysUser(intradayUserId).getBalance()),
                "both paths must leave identical balances");
        assertEquals(0, sysUser(settleUserId).getDebt().compareTo(sysUser(intradayUserId).getDebt()),
                "both paths must leave identical debts");
    }

    // ---- 造数 helper ----

    private long seedInMarket() {
        long userId = worldSeeder.seedUser("margin_svc_" + System.nanoTime(), "$2a$10$bcrypthash");
        setWorldSeed(userId, 20260904L);
        marketStateService.startDay(userId);
        return userId;
    }

    private void setWorldSeed(long userId, long worldSeed) {
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(worldSeed);
        userGameStateMapper.updateById(gs);
    }

    private void setState(long userId, String marketState) {
        UserGameState gs = gameState(userId);
        gs.setMarketState(marketState);
        userGameStateMapper.updateById(gs);
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

    private void insertPosition(long userId, long stockId, int shares, int availableShares, String avgCost) {
        UserPosition position = new UserPosition();
        position.setUserId(userId);
        position.setStockId(stockId);
        position.setShares(shares);
        position.setAvailableShares(availableShares);
        position.setAvgCost(new BigDecimal(avgCost));
        userPositionMapper.insert(position);
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

    private QuoteBoard board(long userId) {
        return quoteBoardRegistry.getOrLoad(userId);
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

    private UserPosition position(long userId, long stockId) {
        return userPositionMapper.selectOne(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId)
                .eq(UserPosition::getStockId, stockId));
    }

    private List<UserPosition> positions(long userId) {
        return userPositionMapper.selectList(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId));
    }

    private List<TradeRecord> records(long userId) {
        return tradeRecordMapper.selectList(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId));
    }

    private List<LoanRecord> loans(long userId) {
        return loanRecordMapper.selectList(new LambdaQueryWrapper<LoanRecord>()
                .eq(LoanRecord::getUserId, userId));
    }

    private List<UserMessage> messages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
    }

    private static BigDecimal asDecimal(String value) {
        return new BigDecimal(value);
    }

    private void assertCode(ErrorCode expected, Runnable action) {
        BizException ex = assertThrows(BizException.class, action::run,
                expected.name() + " expected but no exception was thrown");
        assertEquals(expected, ex.getErrorCode(), "error code mismatch");
    }

    private void assertCode(ErrorCode expected, Runnable action, String message) {
        BizException ex = assertThrows(BizException.class, action::run, message);
        assertEquals(expected, ex.getErrorCode(), message);
    }
}
