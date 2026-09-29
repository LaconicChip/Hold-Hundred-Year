package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.ws.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 世界调度器（spec §5）：每 {@link GameConstants#TICK_MS} 毫秒一轮（fixedDelay，上一轮
 * 跑完再计时），遍历在线用户（{@link WsSessionRegistry}）逐一推进各自世界——
 * 上午/下午盘中态推一跳；午休态按挂钟计时满 {@link GameConstants#LUNCH_MS} 恢复下午
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
     * 心跳级预判：板子还没缓存（首次/刚重启）必须放行去加载；已缓存则按本局 tickMs
     * 判定是否到点。午休期间由 resumeLunchIfDue 打一次 lastTickAtNanos 戳，把午休
     * 检查也节流到"每跳一次"，避免 100ms 心跳变成每 100ms 查一次库。
     */
    private boolean dueForBeat(long userId) {
        QuoteBoard board = quoteBoardRegistry.peek(userId);
        if (board == null) {
            return true;
        }
        return board.tickElapsedMillis() >= board.getGameMode().getTickMs();
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
