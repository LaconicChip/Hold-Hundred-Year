package com.holdhundredyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.model.dto.SharkLoanDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.LoanRecord;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.entity.UserSharkLoan;
import com.holdhundredyear.market.mapper.LoanRecordMapper;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.mapper.UserSharkLoanMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高利贷集成测试（hold_year_test 库，方法级事务自动回滚），覆盖 spec §6.6：
 * 额度 5 倍净资产、日息按账龄递增且利滚利、到期先扣现金再强卖、卖不掉则逾期、
 * 高利贷不进维持担保比例但进净资产与资不抵债判定（含"只有高利贷、没有融资债"这一条），
 * 以及提前还款按到期日近的优先冲销。
 */
@SpringBootTest
@Transactional
class SharkServiceTest {

    private static final BigDecimal BASE = new BigDecimal("100000.00");

    @Autowired
    private WorldSeeder worldSeeder;
    @Autowired
    private SharkService sharkService;
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
    private UserSharkLoanMapper sharkLoanMapper;
    @Autowired
    private TradeRecordMapper tradeRecordMapper;
    @Autowired
    private LoanRecordMapper loanRecordMapper;
    @Autowired
    private UserMessageMapper userMessageMapper;

    @Test
    void borrowUpToFiveTimesNetAssetThenRejectsMore() {
        long userId = seedInMarket();

        SharkLoanDTO result = sharkService.borrow(userId, money("500000.00"));

        assertEquals(0, money("600000.00").compareTo(balance(userId)), "cash 100000 + 500000");
        assertEquals(0, money("500000.00").compareTo(sharkOf(userId)), "the ledger must sync into sys_user");
        assertEquals(0, money("0.00").compareTo(result.getBorrowable()),
                "borrowing exactly 5x net asset leaves no room (net asset is unchanged by the draw)");
        List<LoanRecord> loans = loanRecords(userId);
        assertEquals(1, loans.size());
        assertEquals("SHARK_BORROW", loans.get(0).getLoanType());
        assertEquals(0, money("500000.00").compareTo(loans.get(0).getDebtAfter()));
        assertNotNull(message(userId, "高利贷到手"), "the draw must be announced");

        assertThrows(BizException.class, () -> sharkService.borrow(userId, money("0.01")),
                "the cap is hard: one cent more must be refused");
    }

    @Test
    void dailyRateEscalatesByAgeAndCompounds() {
        UserSharkLoan loan = new UserSharkLoan();
        loan.setStartDay(1);
        assertEquals(0.24, SharkService.annualRateAt(loan, 1), 1e-9, "fresh money is the cheapest it will ever be");
        assertEquals(0.24, SharkService.annualRateAt(loan, 5), 1e-9, "step two is still day 5");
        assertEquals(0.36, SharkService.annualRateAt(loan, 6), 1e-9, "day 6 buys the second step");
        assertEquals(0.72, SharkService.annualRateAt(loan, 26), 1e-9, "capped at 72% annual");
        assertEquals(0.72, SharkService.annualRateAt(loan, 999), 1e-9, "and stays capped");

        assertEquals(0, money("4000.00").compareTo(SharkService.dailyInterestOf(
                money("400000.00"), 0.24, GameMode.STANDARD)), "standard: 1% a day");
        assertEquals(0, money("8000.00").compareTo(SharkService.dailyInterestOf(
                money("400000.00"), 0.24, GameMode.QUICK)), "quick: a game-year is 12 days, so a day costs double");

        long userId = seedInMarket();
        sharkService.borrow(userId, money("400000.00"));
        settleDay(userId, 1);
        assertEquals(0, money("404000.00").compareTo(sharkOf(userId)), "4000 of interest rolls into the balance");
        settleDay(userId, 2);
        assertEquals(0, money("408040.00").compareTo(sharkOf(userId)),
                "tomorrow charges interest on the fatter balance");
        assertEquals(2, loanRecords(userId).stream()
                .filter(record -> "SHARK_INTEREST".equals(record.getLoanType())).count());
    }

    @Test
    void maturitySellsPositionsWhenCashIsNotEnough() {
        long userId = seedInMarket();
        sharkService.borrow(userId, money("400000.00"));
        setUserBalance(userId, "0.00");
        insertPosition(userId, 1L, 3000, 3000, "168.00");

        settleDay(userId, 25);

        List<UserSharkLoan> open = openLoans(userId);
        assertTrue(open.isEmpty(), "the tranche must be closed out at maturity");
        assertEquals(0, money("0.00").compareTo(sharkOf(userId)), "no balance may survive the due date");
        List<TradeRecord> sells = tradeRecords(userId);
        assertEquals(1, sells.size(), "the position pays for the debt");
        assertEquals(0, money("504000.00").compareTo(sells.get(0).getAmount()), "3000 * 168.00");
        assertNotNull(message(userId, "高利贷到期清算"), "forced sales must be announced");
        assertEquals(1, loanRecords(userId).stream()
                .filter(record -> "SHARK_LIQUIDATE".equals(record.getLoanType())).count());
    }

    @Test
    void sealedLimitDownCannotBeSoldSoTheLoanGoesOverdue() {
        long userId = seedInMarket();
        sharkService.borrow(userId, money("400000.00"));
        setUserBalance(userId, "0.00");
        insertPosition(userId, 1L, 1000, 1000, "168.00");
        Quote sealed = quote(userId, 1L);
        sealed.setCurrent(sealed.limitDownPrice());

        settleDay(userId, 25);

        List<UserSharkLoan> open = openLoans(userId);
        assertEquals(1, open.size(), "a sealed limit-down stock cannot be force-sold");
        assertEquals(SharkService.STATUS_OVERDUE, open.get(0).getStatus());
        assertTrue(sharkOf(userId).signum() > 0, "the debt survives, and so does the interest");
        assertTrue(tradeRecords(userId).isEmpty(), "nothing may be sold through a sealed board");
        assertNotNull(message(userId, "高利贷逾期"));
    }

    @Test
    void sharkDebtStaysOutOfTheGuardRatioButNotOutOfNetAsset() {
        long userId = seedInMarket();
        setUserBalance(userId, "100000.00");
        setUserDebt(userId, "10000.00");
        setUserSharkDebt(userId, "500000.00");
        insertPosition(userId, 1L, 3000, 3000, "168.00");

        boolean bankrupt = marginService.settleCheck(userId, gameState(userId), sysUser(userId),
                positions(userId), board(userId));

        assertEquals(0, sharkOf(userId).compareTo(money("500000.00")),
                "the broker must not liquidate a position to cover a private loan");
        assertTrue(tradeRecords(userId).isEmpty(), "no forced sale: the margin ratio 504000/10000 is fine");
        assertEquals(false, bankrupt, "net asset 100000 + 504000 - 10000 - 500000 is still positive");
    }

    @Test
    void insolventThroughSharkAloneStillEndsOnTheRooftop() {
        // 只有高利贷、没有融资债时，marginCheck 不能因为"融资负债为 0"就整体短路：
        // 5 倍仓位碰上一个跌停日，净值直接归零，这局必须结束。
        long userId = seedInMarket();
        setUserBalance(userId, "0.00");
        setUserDebt(userId, "0.00");
        insertPosition(userId, 1L, 3000, 3000, "168.00");
        Quote sealed = quote(userId, 1L);
        sealed.setCurrent(sealed.limitDownPrice());
        BigDecimal mark = money(String.valueOf(sealed.getCurrent())).multiply(new BigDecimal("3000"));
        setUserSharkDebt(userId, mark.toPlainString());

        boolean bankrupt = marginService.settleCheck(userId, gameState(userId), sysUser(userId),
                positions(userId), board(userId));

        assertTrue(bankrupt, "持仓市值 " + mark + " 与高利贷余额相等，净资产归零就是爆仓（判定是 <= 0）");
        assertNotNull(message(userId, "爆仓"));
        assertEquals(0, sharkOf(userId).compareTo(mark), "the check must not touch the ledger");
    }

    @Test
    void earlyRepayClearsTheNearestDueDateFirst() {
        long userId = seedInMarket();
        sharkService.borrow(userId, money("100000.00"));
        SharkLoanDTO afterSecond = sharkService.borrow(userId, money("50000.00"));
        assertEquals(2, afterSecond.getTranches().size(), "two tranches, each with its own due date");
        setUserBalance(userId, "120000.00");

        SharkLoanDTO repaid = sharkService.repay(userId, money("120000.00"));

        assertEquals(0, money("30000.00").compareTo(repaid.getBalance()),
                "the earlier tranche is cleared first, the remainder rolls onto the second");
        List<UserSharkLoan> open = openLoans(userId);
        assertEquals(1, open.size());
        assertEquals(0, money("30000.00").compareTo(open.get(0).getBalance()));
        assertEquals(0, money("30000.00").compareTo(sharkOf(userId)), "sys_user must mirror the ledger");
    }

    @Test
    void chattersOnlyOnMilestonesAndStillAccruesEveryDay() {
        long userId = seedInMarket();
        sharkService.borrow(userId, money("100000.00"));
        // 标准模式期限 24 个交易日：到期日第 25 天，所以第 2~12 天里只有两次"涨档"
        //（账龄满 5 天、满 10 天），临近到期提醒（剩 3 天 / 剩 1 天）还没到点。
        for (int day = 2; day <= 12; day++) {
            settleDay(userId, day);
        }

        List<String> notices = messages(userId).stream().map(UserMessage::getTitle)
                .filter((title) -> "高利贷涨息".equals(title) || "高利贷临近到期".equals(title)
                        || "高利贷逾期".equals(title) || "高利贷到期清算".equals(title)
                        || "高利贷结清".equals(title))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, notices.size(),
                "11 个结算日里只该被打扰 2 次（涨档），其余静默: " + notices);

        long interestRows = loanRecords(userId).stream()
                .filter((row) -> "SHARK_INTEREST".equals(row.getLoanType())).count();
        assertEquals(11, interestRows,
                "少发提醒不等于少算利息：每一天都得有结息流水");
        assertTrue(sharkOf(userId).compareTo(money("100000.00")) > 0,
                "the balance must have compounded upward");
    }

    @Test
    void zeroOrNegativeAmountsAreRejected() {
        long userId = seedInMarket();
        assertThrows(BizException.class, () -> sharkService.borrow(userId, money("0.00")));
        assertThrows(BizException.class, () -> sharkService.borrow(userId, money("-100.00")));
        assertThrows(BizException.class, () -> sharkService.repay(userId, money("0.00")));
    }

    @Test
    void borrowingIsRefusedOnceTheRunIsOver() {
        long userId = seedInMarket();
        setState(userId, "FINISHED");
        BizException ex = assertThrows(BizException.class,
                () -> sharkService.borrow(userId, money("1000.00")));
        assertEquals(ErrorCode.FINISHED_STATE, ex.getErrorCode());
    }

    // ---- 造数 helper（与 MarginServiceTest 同一套锁协议） ----

    private long seedInMarket() {
        long userId = worldSeeder.seedUser("shark_svc_" + System.nanoTime(), "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(20260904L);
        userGameStateMapper.updateById(gs);
        marketStateService.startDay(userId);
        return userId;
    }

    /** 直接跑某一天的收盘计息与到期清算（世界锁内，口径同 SettlementService 的调用）。 */
    private void settleDay(long userId, int day) {
        ReentrantLock lock = quoteBoardRegistry.worldLock(userId);
        lock.lock();
        try {
            UserGameState gs = gameState(userId);
            gs.setCurrentDay(day);
            userGameStateMapper.updateById(gs);
            SysUser user = sysUser(userId);
            sharkService.settleDay(userId, day, user, positions(userId), board(userId),
                    GameMode.of(gs.getGameMode()), gs.getWorldSeed());
            sysUserMapper.updateById(user);
        } finally {
            lock.unlock();
        }
    }

    private Quote quote(long userId, long stockId) {
        ReentrantLock lock = quoteBoardRegistry.worldLock(userId);
        lock.lock();
        try {
            return board(userId).quote(stockId);
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

    private SysUser sysUser(long userId) {
        return sysUserMapper.selectById(userId);
    }

    private BigDecimal balance(long userId) {
        return sysUser(userId).getBalance();
    }

    private BigDecimal sharkOf(long userId) {
        return SharkService.sharkOf(sysUser(userId));
    }

    private void setUserBalance(long userId, String value) {
        SysUser user = sysUser(userId);
        user.setBalance(money(value));
        sysUserMapper.updateById(user);
    }

    private void setUserDebt(long userId, String value) {
        SysUser user = sysUser(userId);
        user.setDebt(money(value));
        sysUserMapper.updateById(user);
    }

    private void setUserSharkDebt(long userId, String value) {
        SysUser user = sysUser(userId);
        user.setSharkDebt(money(value));
        sysUserMapper.updateById(user);
    }

    private void setState(long userId, String marketState) {
        UserGameState gs = gameState(userId);
        gs.setMarketState(marketState);
        userGameStateMapper.updateById(gs);
    }

    private void insertPosition(long userId, long stockId, int shares, int available, String avgCost) {
        UserPosition position = new UserPosition();
        position.setUserId(userId);
        position.setStockId(stockId);
        position.setShares(shares);
        position.setAvailableShares(available);
        position.setAvgCost(money(avgCost));
        userPositionMapper.insert(position);
    }

    private List<UserPosition> positions(long userId) {
        return userPositionMapper.selectList(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId));
    }

    private List<UserSharkLoan> openLoans(long userId) {
        return sharkLoanMapper.selectList(new LambdaQueryWrapper<UserSharkLoan>()
                .eq(UserSharkLoan::getUserId, userId)
                .in(UserSharkLoan::getStatus, List.of(SharkService.STATUS_OPEN, SharkService.STATUS_OVERDUE)));
    }

    private List<TradeRecord> tradeRecords(long userId) {
        return tradeRecordMapper.selectList(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId));
    }

    private List<LoanRecord> loanRecords(long userId) {
        return loanRecordMapper.selectList(new LambdaQueryWrapper<LoanRecord>()
                .eq(LoanRecord::getUserId, userId));
    }

    private List<UserMessage> messages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
    }

    private UserMessage message(long userId, String title) {
        return messages(userId).stream()
                .filter(msg -> title.equals(msg.getTitle()))
                .findFirst().orElse(null);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
