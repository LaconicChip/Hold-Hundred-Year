package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserEra;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserScriptDay;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserEraMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserScriptDayMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.ws.WsSessionRegistry;
import com.holdhunderdyear.world.WorldSeeder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 调度器集成测试（hold_year_test 库）。与引擎测试不同：本类【非事务】——调度器线程
 * 看不到测试事务的未提交数据，播种/开盘必须在独立事务中真实提交；随之清理不能依赖
 * 回滚，@AfterEach 手动删行（先持世界锁再删，杜绝调度器在途读到半删状态）。
 * 覆盖：调度器推进已注册在线用户的世界、世界锁被持有时跳过而非阻塞、且不拖累其余
 * 在线用户照常推进。
 */
@SpringBootTest
class MarketSchedulerTest {

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private WsSessionRegistry wsSessionRegistry;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private QuoteBoardRegistry quoteBoardRegistry;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserStockStateMapper userStockStateMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Autowired
    private UserEraMapper userEraMapper;

    @Autowired
    private UserScriptDayMapper userScriptDayMapper;

    @Autowired
    private UserDailySnapshotMapper userDailySnapshotMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    private long userId;

    private long secondUserId;

    @Test
    void schedulerTicksRegisteredWorld() {
        userId = worldSeeder.seedUser("sched_tick1", "$2a$10$bcrypthash");
        marketStateService.startDay(userId);
        wsSessionRegistry.register(userId);

        // game_state.tick_index 每 5 跳才 checkpoint 落库，断言锚点用行情板计数——
        // 测试线程经世界锁读（与调度线程 unlock→lock 建立 happens-before），可见性安全
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                boardTick(userId) >= 2);

        assertEquals("MORNING_OPEN", gameState(userId).getMarketState(),
                "a few scheduler ticks must not carry the world past the morning session");
    }

    @Test
    void schedulerSkipsHeldWorldWithoutBlockingOthers() {
        userId = worldSeeder.seedUser("sched_hold1", "$2a$10$bcrypthash");
        secondUserId = worldSeeder.seedUser("sched_hold2", "$2a$10$bcrypthash");
        marketStateService.startDay(userId);
        marketStateService.startDay(secondUserId);
        ReentrantLock lock = quoteBoardRegistry.worldLock(userId);
        lock.lock();
        try {
            // 先持锁再注册：若先注册后持锁，调度器可能在间隙里推跳，断言会 flaky
            wsSessionRegistry.register(userId);
            wsSessionRegistry.register(secondUserId);
            // 第二用户世界锁自由：持锁期间即被调度器推进——跳过持锁者不拖累其余用户
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                    boardTick(secondUserId) >= 1);
            assertEquals(0, boardTick(userId),
                    "world lock held by a user thread: scheduler must skip, never block");
            assertEquals(0, gameState(userId).getTickIndex());
        } finally {
            lock.unlock();
        }
    }

    @AfterEach
    void cleanup() {
        cleanupUser(userId);
        cleanupUser(secondUserId);
    }

    private void cleanupUser(long uid) {
        if (uid == 0) {
            return;
        }
        ReentrantLock lock = quoteBoardRegistry.worldLock(uid);
        lock.lock();
        try {
            wsSessionRegistry.unregister(uid);
            quoteBoardRegistry.evict(uid);
            userStockStateMapper.delete(new LambdaQueryWrapper<UserStockState>()
                    .eq(UserStockState::getUserId, uid));
            userGameStateMapper.delete(new LambdaQueryWrapper<UserGameState>()
                    .eq(UserGameState::getUserId, uid));
            userMessageMapper.delete(new LambdaQueryWrapper<UserMessage>()
                    .eq(UserMessage::getUserId, uid));
            userScriptDayMapper.delete(new LambdaQueryWrapper<UserScriptDay>()
                    .eq(UserScriptDay::getUserId, uid));
            userEraMapper.delete(new LambdaQueryWrapper<UserEra>()
                    .eq(UserEra::getUserId, uid));
            userDailySnapshotMapper.delete(new LambdaQueryWrapper<UserDailySnapshot>()
                    .eq(UserDailySnapshot::getUserId, uid));
            sysUserMapper.deleteById(uid);
        } finally {
            lock.unlock();
        }
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private int boardTick(long uid) {
        ReentrantLock lock = quoteBoardRegistry.worldLock(uid);
        lock.lock();
        try {
            return quoteBoardRegistry.getOrLoad(uid).getTickIndex();
        } finally {
            lock.unlock();
        }
    }
}
