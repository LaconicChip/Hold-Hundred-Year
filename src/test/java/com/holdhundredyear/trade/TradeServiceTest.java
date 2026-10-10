package com.holdhundredyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.model.dto.TradeDTO;
import com.holdhundredyear.game.model.dto.TradeResultDTO;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交易服务集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖 Phase 6 交易规格：整手校验、涨跌停单向封死、停牌、T+1 冻结、
 * 佣金（万 3 最低 5 元双边）、印花税（千 1 仅卖出）、加权平均成本、
 * 清仓删行、错误码矩阵与校验链顺序（市场态先行）。
 * 第 1 日开盘价即基准价（IPO 日无行情行为），金额期望值全部硬编码。
 */
@SpringBootTest
@Transactional
class TradeServiceTest {

    @Autowired private WorldSeeder worldSeeder;
    @Autowired private MarketStateService marketStateService;
    @Autowired private TradeService tradeService;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserPositionMapper userPositionMapper;
    @Autowired private TradeRecordMapper tradeRecordMapper;

    @Test
    void buyDeductsBalanceAndFreezesAllBoughtShares() {
        long userId = seedInMarket();

        TradeResultDTO result = tradeService.buy(userId, order(1L, 100));

        assertEquals(0, asDecimal("83194.96").compareTo(balance(userId)),
                "16800.00 amount + 5.04 commission must be deducted from 100000.00");
        UserPosition position = position(userId, 1L);
        assertEquals(100, position.getShares(), "bought shares must be added");
        assertEquals(0, position.getAvailableShares(), "T+1: bought shares must stay frozen");
        assertEquals(0, asDecimal("168.05").compareTo(position.getAvgCost()),
                "16805.04 / 100 = 168.0504 -> 168.05");
        assertEquals(0, asDecimal("168.00").compareTo(result.getPrice()));
        assertEquals(100, result.getShares());
        assertEquals(0, asDecimal("16800.00").compareTo(result.getAmount()));
        assertEquals(0, asDecimal("5.04").compareTo(result.getCommission()));
        assertEquals(0, asDecimal("0.00").compareTo(result.getTax()));
        assertEquals(0, asDecimal("83194.96").compareTo(result.getBalance()));
        List<TradeRecord> records = records(userId);
        assertEquals(1, records.size(), "exactly one BUY record must be written");
        TradeRecord record = records.get(0);
        assertEquals("BUY", record.getTradeType());
        assertEquals(0, asDecimal("168.00").compareTo(record.getPrice()));
        assertEquals(100, record.getShares());
        assertEquals(0, asDecimal("16800.00").compareTo(record.getAmount()));
        assertEquals(0, asDecimal("5.04").compareTo(record.getCommission()));
        assertEquals(0, BigDecimal.ZERO.compareTo(record.getTax()),
                "buy must not be charged stamp tax");
        assertEquals(1, record.getGameDay(), "record must carry the current game day");
    }

    @Test
    void commissionChargesMinimumFiveYuanOnSmallOrders() {
        long userId = seedInMarket();

        tradeService.buy(userId, order(19L, 100));

        assertEquals(0, asDecimal("5.00").compareTo(records(userId).get(0).getCommission()),
                "450.00 * 0.0003 = 0.14 is below the floor: commission must be 5.00");
        assertEquals(0, asDecimal("99545.00").compareTo(balance(userId)),
                "100000.00 - 450.00 - 5.00 = 99545.00");
        assertEquals(0, asDecimal("4.55").compareTo(position(userId, 19L).getAvgCost()),
                "455.00 / 100 = 4.55");
    }

    @Test
    void addPositionWeightedAveragesCost() {
        long userId = seedInMarket();
        insertPosition(userId, 2L, 100, 0, "50.00");

        tradeService.buy(userId, order(2L, 100));

        UserPosition position = position(userId, 2L);
        assertEquals(200, position.getShares());
        assertEquals(0, asDecimal("69.03").compareTo(position.getAvgCost()),
                "(100*50.00 + 8800.00 + 5.00) / 200 = 69.025 -> 69.03");
        assertEquals(0, asDecimal("91195.00").compareTo(balance(userId)),
                "100000.00 - 8800.00 - 5.00 = 91195.00");
    }

    @Test
    void sellCreditsNetProceedsAndKeepsAvgCost() {
        long userId = seedInMarket();
        insertPosition(userId, 2L, 200, 200, "88.00");

        TradeResultDTO result = tradeService.sell(userId, order(2L, 100));

        assertEquals(0, asDecimal("108786.20").compareTo(balance(userId)),
                "8800.00 - 5.00 commission - 8.80 tax = 8786.20 net proceeds");
        UserPosition position = position(userId, 2L);
        assertEquals(100, position.getShares());
        assertEquals(100, position.getAvailableShares(), "selling must only consume available shares");
        assertEquals(0, asDecimal("88.00").compareTo(position.getAvgCost()),
                "selling must not touch avg cost");
        TradeRecord record = records(userId).get(0);
        assertEquals("SELL", record.getTradeType());
        assertEquals(0, asDecimal("8.80").compareTo(record.getTax()),
                "sell must be charged 0.1% stamp tax");
        assertEquals(0, asDecimal("5.00").compareTo(record.getCommission()));
        assertEquals(0, asDecimal("108786.20").compareTo(result.getBalance()));
    }

    @Test
    void sellingEverythingDeletesThePositionRow() {
        long userId = seedInMarket();
        insertPosition(userId, 2L, 100, 100, "88.00");

        tradeService.sell(userId, order(2L, 100));

        assertNull(position(userId, 2L), "a fully closed position row must be deleted");
        assertEquals(0, asDecimal("108786.20").compareTo(balance(userId)));
    }

    @Test
    void closedMarketRejectsBothSides() {
        long userId = seedInMarket();
        setState(userId, "CLOSED");

        assertCode(ErrorCode.MARKET_CLOSED, () -> tradeService.buy(userId, order(2L, 100)));
        assertCode(ErrorCode.MARKET_CLOSED, () -> tradeService.sell(userId, order(2L, 100)));
    }

    @Test
    void lunchBreakRejectsBothSides() {
        long userId = seedInMarket();
        setState(userId, "LUNCH_BREAK");

        assertCode(ErrorCode.NOT_TRADING_TIME, () -> tradeService.buy(userId, order(2L, 100)));
        assertCode(ErrorCode.NOT_TRADING_TIME, () -> tradeService.sell(userId, order(2L, 100)));
    }

    @Test
    void finishedAndBankruptStatesRejectOrders() {
        long userId = seedInMarket();
        setState(userId, "FINISHED");

        assertCode(ErrorCode.FINISHED_STATE, () -> tradeService.buy(userId, order(2L, 100)));
        assertCode(ErrorCode.FINISHED_STATE, () -> tradeService.sell(userId, order(2L, 100)));

        setState(userId, "BANKRUPT");
        assertCode(ErrorCode.FINISHED_STATE, () -> tradeService.buy(userId, order(2L, 100)));
    }

    @Test
    void limitUpSealRejectsBuyButSellStillGoesThrough() {
        long userId = seedInMarket();
        Quote quote = quote(userId, 2L);
        quote.setCurrent(quote.limitUpPrice());

        assertCode(ErrorCode.LIMIT_UP_CANNOT_BUY, () -> tradeService.buy(userId, order(2L, 100)));
        assertEquals(0, asDecimal("100000.00").compareTo(balance(userId)),
                "rejected buy must not touch cash");
        assertTrue(records(userId).isEmpty(), "rejected buy must not leave a record");

        insertPosition(userId, 2L, 100, 100, "88.00");
        tradeService.sell(userId, order(2L, 100));
        assertEquals(0, asDecimal("109665.32").compareTo(balance(userId)),
                "selling into a sealed limit-up is allowed: 9680.00 - 5.00 - 9.68 = 9665.32");
    }

    @Test
    void limitDownSealRejectsSellButBuyStillGoesThrough() {
        long userId = seedInMarket();
        Quote quote = quote(userId, 2L);
        quote.setCurrent(quote.limitDownPrice());
        insertPosition(userId, 2L, 100, 100, "88.00");

        assertCode(ErrorCode.LIMIT_DOWN_CANNOT_SELL, () -> tradeService.sell(userId, order(2L, 100)));
        assertTrue(records(userId).isEmpty(), "rejected sell must not leave a record");

        tradeService.buy(userId, order(2L, 100));
        assertEquals(0, asDecimal("92075.00").compareTo(balance(userId)),
                "buying into a sealed limit-down is allowed: 7920.00 + 5.00 = 7925.00");
        UserPosition position = position(userId, 2L);
        assertEquals(200, position.getShares());
        assertEquals(0, asDecimal("83.63").compareTo(position.getAvgCost()),
                "(100*88.00 + 7925.00) / 200 = 83.625 -> 83.63");
    }

    @Test
    void haltedStockRejectsBothSides() {
        long userId = seedInMarket();
        quote(userId, 2L).setHalted(true);

        assertCode(ErrorCode.HALTED, () -> tradeService.buy(userId, order(2L, 100)));
        assertCode(ErrorCode.HALTED, () -> tradeService.sell(userId, order(2L, 100)));
        assertEquals(0, asDecimal("100000.00").compareTo(balance(userId)),
                "halted stock orders must not touch cash");
    }

    @Test
    void insufficientCashRejectsBuyWithoutSideEffects() {
        long userId = seedInMarket();
        SysUser user = sysUser(userId);
        user.setBalance(asDecimal("100.00"));
        sysUserMapper.updateById(user);

        assertCode(ErrorCode.INSUFFICIENT_CASH, () -> tradeService.buy(userId, order(1L, 100)));
        assertEquals(0, asDecimal("100.00").compareTo(balance(userId)),
                "failed buy must not touch cash");
        assertNull(position(userId, 1L), "failed buy must not create a position");
        assertTrue(records(userId).isEmpty(), "failed buy must not leave a record");
    }

    @Test
    void insufficientSharesRejectsSell() {
        long userId = seedInMarket();
        insertPosition(userId, 2L, 100, 50, "88.00");

        assertCode(ErrorCode.INSUFFICIENT_SHARES, () -> tradeService.sell(userId, order(2L, 100)));
        assertCode(ErrorCode.INSUFFICIENT_SHARES, () -> tradeService.sell(userId, order(1L, 100)),
                "selling a stock never held must also be INSUFFICIENT_SHARES");
        UserPosition position = position(userId, 2L);
        assertEquals(100, position.getShares(), "failed sell must not touch shares");
        assertEquals(50, position.getAvailableShares());
        assertTrue(records(userId).isEmpty());
    }

    @Test
    void nonLotQuantitiesAreRejected() {
        long userId = seedInMarket();

        assertCode(ErrorCode.NOT_LOT, () -> tradeService.buy(userId, order(2L, 50)));
        assertCode(ErrorCode.NOT_LOT, () -> tradeService.buy(userId, order(2L, 0)));
        assertCode(ErrorCode.NOT_LOT, () -> tradeService.buy(userId, order(2L, -100)));
        assertCode(ErrorCode.NOT_LOT, () -> tradeService.sell(userId, order(2L, 50)));
    }

    @Test
    void unknownStockIsRejected() {
        long userId = seedInMarket();

        assertCode(ErrorCode.STOCK_NOT_FOUND, () -> tradeService.buy(userId, order(999L, 100)));
        assertCode(ErrorCode.STOCK_NOT_FOUND, () -> tradeService.sell(userId, order(999L, 100)));
    }

    @Test
    void marketStateIsCheckedBeforeLotSize() {
        long userId = seedInMarket();
        setState(userId, "CLOSED");

        assertCode(ErrorCode.MARKET_CLOSED, () -> tradeService.buy(userId, order(2L, 50)),
                "market state must short-circuit before the lot check");
    }

    @Test
    void stStockTradesWithinFivePercentBand() {
        long userId = seedInMarket();
        Quote quote = quote(userId, 19L);
        quote.setSt(true);
        quote.setCurrent(quote.limitUpPrice());

        assertCode(ErrorCode.LIMIT_UP_CANNOT_BUY, () -> tradeService.buy(userId, order(19L, 100)),
                "4.50*1.05=4.73 must seal an ST stock (a 10% band would allow it)");

        insertPosition(userId, 19L, 100, 100, "4.50");
        quote.setCurrent(quote.limitDownPrice());
        assertCode(ErrorCode.LIMIT_DOWN_CANNOT_SELL, () -> tradeService.sell(userId, order(19L, 100)),
                "4.50*0.95=4.28 must seal an ST stock (a 10% band would allow it)");

        quote.setCurrent(asDecimal("4.60").doubleValue());
        tradeService.sell(userId, order(19L, 100));
        assertEquals(0, asDecimal("100454.54").compareTo(balance(userId)),
                "4.60 is inside the ST band: 100000.00 + 460.00 - 5.00 - 0.46 = 100454.54");
    }

    private long seedInMarket() {
        long userId = worldSeeder.seedUser("trade_svc_" + System.nanoTime(), "$2a$10$bcrypthash");
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

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private TradeDTO order(long stockId, int shares) {
        TradeDTO dto = new TradeDTO();
        dto.setStockId(stockId);
        dto.setShares(shares);
        return dto;
    }

    /** 改价 helper：与世界锁互斥后改内存现价（锁协议与被测服务一致）。 */
    private Quote quote(long userId, long stockId) {
        ReentrantLock lock = quoteBoardRegistry.worldLock(userId);
        lock.lock();
        try {
            return quoteBoardRegistry.getOrLoad(userId).quote(stockId);
        } finally {
            lock.unlock();
        }
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

    private void insertPosition(long userId, long stockId, int shares, int availableShares, String avgCost) {
        UserPosition position = new UserPosition();
        position.setUserId(userId);
        position.setStockId(stockId);
        position.setShares(shares);
        position.setAvailableShares(availableShares);
        position.setAvgCost(new BigDecimal(avgCost));
        userPositionMapper.insert(position);
    }

    private List<TradeRecord> records(long userId) {
        return tradeRecordMapper.selectList(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId));
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
