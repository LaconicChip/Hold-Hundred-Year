package com.holdhundredyear.market;

import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.ws.PushService;
import org.springframework.stereotype.Service;

/**
 * 快进服务（spec §5 快进按钮）：把当前交易日一路推进到收官。与调度器相反，用户主动
 * 操作可等锁——全程持有用户世界锁，循环内重读 game_state 决定下一步：
 * <ul>
 *   <li>MORNING_OPEN/AFTERNOON_OPEN：tickOne 推一跳（同线程重入世界锁不阻塞）；</li>
 *   <li>LUNCH_BREAK：立即 resumeAfternoon（不掷任何随机数，不影响确定性），并清零
 *       午休打点；</li>
 *   <li>NOT_STARTED/CLOSED：本次 skip 至多 startDay 一次——进过盘中后再次读到 CLOSED
 *       即当日收官，立即返回（一次快进只推一日，不连环开日；startDay 同线程重入世界
 *       锁不阻塞）；</li>
 *   <li>SETTLING：遗留结算态，防御性终止；</li>
 *   <li>BANKRUPT/FINISHED：本局已结束，终止。</li>
 * </ul>
 * 无 game_state 抛 NOT_FOUND（与 MarketStateService 惯例一致）。
 */
@Service
public class FastForwardService {

    private final QuoteBoardRegistry quoteBoardRegistry;
    private final MarketStateService marketStateService;
    private final WorldTickService worldTickService;
    private final PushService pushService;

    public FastForwardService(QuoteBoardRegistry quoteBoardRegistry,
                              MarketStateService marketStateService,
                              WorldTickService worldTickService,
                              PushService pushService) {
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.marketStateService = marketStateService;
        this.worldTickService = worldTickService;
        this.pushService = pushService;
    }

    /** 快进到当日收官：盘中逐跳、午休立即恢复、未开盘先开日；收官即返回。 */
    public void skip(long userId) {
        quoteBoardRegistry.worldLock(userId).lock();
        pushService.suspendQuotes(userId); // 快进全程行情静默（Task 10.2）
        try {
            boolean openedThisSkip = false;
            boolean enteredTrading = false;
            while (true) {
                UserGameState gs = marketStateService.requireGameState(userId);
                MarketState state = MarketState.valueOf(gs.getMarketState());
                switch (state) {
                    case MORNING_OPEN, AFTERNOON_OPEN -> {
                        enteredTrading = true;
                        worldTickService.tickOne(userId);
                    }
                    case LUNCH_BREAK -> {
                        enteredTrading = true;
                        marketStateService.resumeAfternoon(userId);
                        quoteBoardRegistry.getOrLoad(userId).clearLunchTiming();
                    }
                    case NOT_STARTED, CLOSED -> {
                        if (enteredTrading || openedThisSkip) {
                            return;
                        }
                        marketStateService.startDay(userId);
                        openedThisSkip = true;
                    }
                    default -> {
                        return;
                    }
                }
            }
        } finally {
            pushService.resumeQuotes(userId); // 快进结束恢复行情推送
            quoteBoardRegistry.worldLock(userId).unlock();
        }
    }
}
