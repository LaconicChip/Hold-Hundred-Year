package com.holdhundredyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.ws.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 世界调度器（spec §5）：每 {@link GameConstants#SCHEDULER_HEARTBEAT_MS} 毫秒一轮（fixedDelay，上一轮
 * 跑完再计时），遍历在线用户（{@link WsSessionRegistry}）逐一推进各自世界——
 * 上午/下午盘中态按各账户本局跳间隔推一跳；午休态按挂钟计时满午休长度恢复下午
 * （首个午休轮懒打点，重启后从零重计，可接受）；未开盘/已收盘/结算/终态跳过。
 *
 * <p>并发协议（P5）：每用户世界锁 tryLock 获取，失败（用户交易/快进持锁）立即跳过该
 * 用户本轮，绝不阻塞调度线程；成功后在锁内重读 game_state（注册到执行之间状态可能
 * 已变，勿用陈旧状态；gs 为空说明刚被注销/清理，同样跳过），tickOne 同线程重入世界锁
 * 不阻塞。调度器只服务注册为在线的用户，其余用户的世界不受影响。
 *
 * <p>故障隔离：单用户本轮推进抛异常只记 warn 跳过，不中断整轮——快照中排在其后的
 * 用户不得被饿死。
 */
@Slf4j
@Service
public class MarketScheduler {

    private final WsSessionRegistry wsSessionRegistry;
    private final QuoteBoardRegistry quoteBoardRegistry;
    private final UserGameStateMapper userGameStateMapper;
    private final WorldTickService worldTickService;
    private final MarketStateService marketStateService;

    /** 冷用户（无缓存行情板）上次放行的纳秒戳：见 {@link #dueForBeat}。 */
    private final Map<Long, Long> coldBeatAtNanos = new ConcurrentHashMap<>();

    /** 冷用户放行间隔：一秒。比心跳密不了多少，但把每秒 10 次查库压成 1 次。 */
    private static final long COLD_BEAT_INTERVAL_NANOS = 1_000_000_000L;

    public MarketScheduler(WsSessionRegistry wsSessionRegistry,
                           QuoteBoardRegistry quoteBoardRegistry,
                           UserGameStateMapper userGameStateMapper,
                           WorldTickService worldTickService,
                           MarketStateService marketStateService) {
        this.wsSessionRegistry = wsSessionRegistry;
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.userGameStateMapper = userGameStateMapper;
        this.worldTickService = worldTickService;
        this.marketStateService = marketStateService;
    }

    /** 调度主循环：遍历在线用户快照，逐人 tryLock 推进一跳或午休计时。 */
    @Scheduled(fixedDelay = GameConstants.SCHEDULER_HEARTBEAT_MS)
    public void tickOnlineWorlds() {
        for (long userId : wsSessionRegistry.onlineUserIds()) {
            try {
                // 玩家正被模态弹窗挡住（重要消息）：这一份世界冻住，午休计时也一并停，
                // 关掉弹窗（或刷新页面重新连上）就继续。只看内存标记，不查库、不抢锁。
                QuoteBoard pausedBoard = quoteBoardRegistry.peek(userId);
                if (pausedBoard != null && pausedBoard.isPaused()) {
                    continue;
                }
                // 100ms 一拍，但绝大多数拍在这一步就被内存预判挡掉：不抢世界锁、不查库。
                // 只有真的到了本局跳间隔（或板子还没缓存、午休待恢复）才往下走。
                if (!dueForBeat(userId)) {
                    continue;
                }
                ReentrantLock lock = quoteBoardRegistry.worldLock(userId);
                if (!lock.tryLock()) {
                    continue;
                }
                try {
                    UserGameState gs = userGameStateMapper.selectOne(
                            new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
                    if (gs == null) {
                        continue;
                    }
                    MarketState state = MarketState.valueOf(gs.getMarketState());
                    switch (state) {
                        case MORNING_OPEN, AFTERNOON_OPEN -> {
                            // 心跳比最快的模式还密，这里按本局跳速门控：没到点就空过这一拍
                            if (dueToTick(userId)) {
                                worldTickService.tickOne(userId);
                            }
                        }
                        case LUNCH_BREAK -> resumeLunchIfDue(userId);
                        default -> {
                        }
                    }
                } finally {
                    lock.unlock();
                }
            } catch (RuntimeException e) {
                log.warn("scheduler tick failed for user {}, skipping this round", userId, e);
            }
        }
    }

    /**
     * 心跳级预判：已缓存行情板的用户按本局 tickMs 判定是否到点（午休期间由
     * resumeLunchIfDue 打一次 lastTickAtNanos 戳，把午休检查也节流成"每跳一次"）。
     *
     * <p>板子还没缓存的用户（注册后还没开过局、进程刚重启、刚登出）没有这个时间戳可依，
     * 一律放行就会让 100ms 心跳退化成"每个在线用户每秒 10 次空查库"——开局前停在
     * 大盘页的每个新号都是这个形状。所以冷用户按 COLD_BEAT_INTERVAL_NANOS 节流：
     * 一秒放行一次，既保住"重启后自动续跑"（下一拍就会 getOrLoad 建板），又不查爆库。
     */
    boolean dueForBeat(long userId) {
        QuoteBoard board = quoteBoardRegistry.peek(userId);
        if (board != null) {
            return board.tickElapsedMillis() >= board.getGameMode().getTickMs();
        }
        long now = System.nanoTime();
        Long last = coldBeatAtNanos.get(userId);
        if (last != null && now - last < COLD_BEAT_INTERVAL_NANOS) {
            return false;
        }
        coldBeatAtNanos.put(userId, now);
        return true;
    }

    /** 锁内二次确认：拿锁期间可能已被别的线程推进过（快进/交易/上一拍）。 */
    private boolean dueToTick(long userId) {
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        return board.tickElapsedMillis() >= board.getGameMode().getTickMs();
    }

    /** 午休恢复：行情板计时满本局 lunchMs 即转下午，并清零打点保证次日重新计时。 */
    private void resumeLunchIfDue(long userId) {
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        if (board.lunchElapsedMillis() < board.getGameMode().getLunchMs()) {
            // 午休没到点：打一拍戳，让 100ms 的心跳把午休检查也节流成每跳一次
            board.setLastTickAtNanos(System.nanoTime());
            return;
        }
        marketStateService.resumeAfternoon(userId);
        board.clearLunchTiming();
    }
}
