package com.holdhundredyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.game.model.dto.TradeDTO;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserIpoRecord;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.entity.UserTitle;
import com.holdhundredyear.market.entity.LoanRecord;
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
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.mapper.UserTitleMapper;
import com.holdhundredyear.ws.WsSessionRegistry;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 交易并发测试（hold_year_test 库，【非事务】——真实提交才能跨线程可见）。
 * 100 线程对同一用户同一股票各买 1 手：世界锁串行化每笔事务，断言资金精确守恒
 * （initial - Σ(amount+commission) == final）、持仓与成交记录严格一致、且全部成交
 * 无一被拒。本类用户不注册 WsSessionRegistry，调度器不推进其世界，第 1 日现价恒为
 * 基准价（IPO 日无行情行为），期望值硬编码。清理不能依赖回滚：@AfterEach 持世界锁
 * 手动删行（模式照抄 MarketSchedulerTest，另加持仓/成交/融资/IPO/称号表）。
 */
@SpringBootTest
class TradeConcurrencyTest {

    private static final long STOCK_ID = 19L;
    private static final int THREADS = 100;

    @Autowired private WorldSeeder worldSeeder;
    @Autowired private MarketStateService marketStateService;
    @Autowired private TradeService tradeService;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private WsSessionRegistry wsSessionRegistry;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserStockStateMapper userStockStateMapper;
    @Autowired private UserPositionMapper userPositionMapper;
    @Autowired private TradeRecordMapper tradeRecordMapper;
    @Autowired private UserMessageMapper userMessageMapper;
    @Autowired private UserDailySnapshotMapper userDailySnapshotMapper;
    @Autowired private LoanRecordMapper loanRecordMapper;
    @Autowired private UserIpoRecordMapper userIpoRecordMapper;
    @Autowired private UserEraMapper userEraMapper;
    @Autowired private UserScriptDayMapper userScriptDayMapper;
    @Autowired private UserTitleMapper userTitleMapper;

    private long userId;

    @Test
    void hundredConcurrentBuysConserveCashExactly() throws Exception {
        userId = worldSeeder.seedUser("trade_cc_" + System.nanoTime(), "$2a$10$bcrypthash");
        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setWorldSeed(20260904L);
        userGameStateMapper.updateById(gs);
        marketStateService.startDay(userId);

        BigDecimal initialBalance = balance(userId);
        assertEquals(0, new BigDecimal("100000.00").compareTo(initialBalance),
                "fresh world must start with the initial balance");
        assertEquals(0, new BigDecimal("4.50").compareTo(boardPrice()),
                "day-1 open must sit at the base price for this test to be deterministic");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> tradeService.buy(userId, order(STOCK_ID, 100))));
            }
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new AssertionError("a concurrent buy was rejected or failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for concurrent buys", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        List<TradeRecord> records = tradeRecordMapper.selectList(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId));
        assertEquals(THREADS, records.size(), "all 100 buys must settle, none rejected");
        BigDecimal totalSpend = BigDecimal.ZERO;
        for (TradeRecord record : records) {
            assertEquals("BUY", record.getTradeType());
            totalSpend = totalSpend.add(record.getAmount()).add(record.getCommission());
        }
        assertEquals(0, initialBalance.subtract(totalSpend).compareTo(balance(userId)),
                "cash must be exactly conserved: initial - sum(amount+commission) == final");

        UserPosition position = userPositionMapper.selectOne(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId)
                .eq(UserPosition::getStockId, STOCK_ID));
        assertEquals(THREADS * 100, position.getShares(), "100 lots of 100 shares");
        assertEquals(0, position.getAvailableShares(), "T+1 must freeze every bought share");
        assertEquals(0, new BigDecimal("4.55").compareTo(position.getAvgCost()),
                "every lot costs 455.00: avg cost must stay 4.55");
        assertEquals(0, new BigDecimal("54500.00").compareTo(balance(userId)),
                "100 * (450.00 + 5.00) = 45500.00 spent from 100000.00");
    }

    @AfterEach
    void cleanup() {
        if (userId == 0) {
            return;
        }
        ReentrantLock lock = quoteBoardRegistry.worldLock(userId);
        lock.lock();
        try {
            wsSessionRegistry.unregister(userId);
            quoteBoardRegistry.evict(userId);
            userPositionMapper.delete(new LambdaQueryWrapper<UserPosition>()
                    .eq(UserPosition::getUserId, userId));
            tradeRecordMapper.delete(new LambdaQueryWrapper<TradeRecord>()
                    .eq(TradeRecord::getUserId, userId));
            userStockStateMapper.delete(new LambdaQueryWrapper<UserStockState>()
                    .eq(UserStockState::getUserId, userId));
            userGameStateMapper.delete(new LambdaQueryWrapper<UserGameState>()
                    .eq(UserGameState::getUserId, userId));
            userMessageMapper.delete(new LambdaQueryWrapper<UserMessage>()
                    .eq(UserMessage::getUserId, userId));
            userDailySnapshotMapper.delete(new LambdaQueryWrapper<UserDailySnapshot>()
                    .eq(UserDailySnapshot::getUserId, userId));
            loanRecordMapper.delete(new LambdaQueryWrapper<LoanRecord>()
                    .eq(LoanRecord::getUserId, userId));
            userIpoRecordMapper.delete(new LambdaQueryWrapper<UserIpoRecord>()
                    .eq(UserIpoRecord::getUserId, userId));
            userEraMapper.delete(new LambdaQueryWrapper<UserEra>()
                    .eq(UserEra::getUserId, userId));
            userScriptDayMapper.delete(new LambdaQueryWrapper<UserScriptDay>()
                    .eq(UserScriptDay::getUserId, userId));
            userTitleMapper.delete(new LambdaQueryWrapper<UserTitle>()
                    .eq(UserTitle::getUserId, userId));
            sysUserMapper.deleteById(userId);
        } finally {
            lock.unlock();
        }
    }

    private BigDecimal balance(long uid) {
        SysUser user = sysUserMapper.selectById(uid);
        return user == null ? BigDecimal.ZERO : user.getBalance();
    }

    private BigDecimal boardPrice() {
        ReentrantLock lock = quoteBoardRegistry.worldLock(userId);
        lock.lock();
        try {
            return BigDecimal.valueOf(quoteBoardRegistry.getOrLoad(userId).quote(STOCK_ID).getCurrent());
        } finally {
            lock.unlock();
        }
    }

    private TradeDTO order(long stockId, int shares) {
        TradeDTO dto = new TradeDTO();
        dto.setStockId(stockId);
        dto.setShares(shares);
        return dto;
    }
}
