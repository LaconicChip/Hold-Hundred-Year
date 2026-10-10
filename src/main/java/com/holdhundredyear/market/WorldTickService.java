package com.holdhundredyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.event.DanmuService;
import com.holdhundredyear.event.EventFactory;
import com.holdhundredyear.event.MainScriptService;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.constants.StockDefs.StockDef;
import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.game.model.enums.SealState;
import com.holdhundredyear.market.engine.IndexCalculator;
import com.holdhundredyear.market.engine.SealMachine;
import com.holdhundredyear.market.engine.VolatilityEngine;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.trade.MarginService;
import com.holdhundredyear.world.WorldRandom;
import com.holdhundredyear.ws.MarketSnapshot;
import com.holdhundredyear.ws.PushService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 世界一跳引擎（spec §5）：行情板每跳推进一步——三层因子收益 + 冲击队列消费 +
 * 磁吸漂移 → round2 并 clamp 到涨跌停价 → 封板状态机 → GARCH 与指数收益均取
 * 钉板后最终实现价（fresh seal 当场钉价、炸板冲击下一跳生效）→ 指数按非停牌股
 * （含封板 0 收益）加权平均推进；随后做熔断判定与阶段转换（45 跳午休 /
 * 90 跳结算 / 熔断提前收盘 / 每 5 跳 checkpoint 落库）。
 *
 * <p>确定性协议（P6/P9）：每跳用 WorldRandom.of(worldSeed, "tick{day}.{tick}") 独立重播种
 * （spec §5 重启恢复：Random 可从 world_seed + current_day + tick_index 重放定位），
 * 消费顺序固定：beginTick（zMkt + 21 板块因子）→ 逐股（StockDefs 声明序）：
 * 未封板股 rawReturn（zI → jump 判定 → jumpK/jumpZ?）→ 封板掷骰（判定 double → 时长 int?，
 * 已封板股仅到期时消费判定 double → 炸板幅度 double?）。停牌股完全跳过（价格冻结、
 * 不入指数、不消费随机数）。
 *
 * <p>并发协议（P5）：每跳重新 getOrLoad 取板（evict 会换实例）；世界锁挂在
 * QuoteBoardRegistry（跨 evict 存活），tickOne 全程持锁并经 TransactionTemplate
 * 锁内提交，与 startDay/交易互斥。盘中恢复兜底：currentEra==null（restore 板）时
 * 先 rederiveDayContext 重算当日上下文。非盘中态静默返回（调用方负责频率）。
 * 事件掷骰已接入；弹幕已接入；主力剧本已接入；WebSocket 推送（Phase 10）后续接入。
 */
@Service
public class WorldTickService {

    private final QuoteBoardRegistry quoteBoardRegistry;
    private final MarketStateService marketStateService;
    private final SettlementService settlementService;
    private final UserGameStateMapper userGameStateMapper;
    private final UserStockStateMapper userStockStateMapper;
    private final MarginService marginService;
    private final EventFactory eventFactory;
    private final DanmuService danmuService;
    private final MainScriptService mainScriptService;
    private final PushService pushService;
    private final TransactionTemplate transactionTemplate;

    private final VolatilityEngine volEngine = new VolatilityEngine();
    private final SealMachine sealMachine = new SealMachine();
    private final IndexCalculator indexCalculator = new IndexCalculator();

    public WorldTickService(QuoteBoardRegistry quoteBoardRegistry,
                            MarketStateService marketStateService,
                            SettlementService settlementService,
                            UserGameStateMapper userGameStateMapper,
                            UserStockStateMapper userStockStateMapper,
                            MarginService marginService,
                            EventFactory eventFactory,
                            DanmuService danmuService,
                            MainScriptService mainScriptService,
                            PushService pushService,
                            PlatformTransactionManager transactionManager) {
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.marketStateService = marketStateService;
        this.settlementService = settlementService;
        this.userGameStateMapper = userGameStateMapper;
        this.userStockStateMapper = userStockStateMapper;
        this.marginService = marginService;
        this.eventFactory = eventFactory;
        this.danmuService = danmuService;
        this.mainScriptService = mainScriptService;
        this.pushService = pushService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 推进一跳：非盘中态静默返回；45 跳自动午休、90 跳自动结算、熔断触及阈值当跳
     *  提前收盘。世界锁内经 TransactionTemplate 执行并提交。
     *  <p>重入契约（spec §5）：调度器/快进须先 worldLock().tryLock()（失败跳过本轮
     *  3 秒 tick）持锁后再调 tickOne（同线程重入不阻塞）；勿在锁外用固定频率调度
     *  裸调——本方法 lock() 阻塞等待，慢跳会把节奏漂移转嫁给调度线程。 */
    public void tickOne(long userId) {
        quoteBoardRegistry.worldLock(userId).lock();
        try {
            transactionTemplate.executeWithoutResult(status -> doTickOne(userId));
        } finally {
            quoteBoardRegistry.worldLock(userId).unlock();
        }
    }

    /** tickOne 事务体（世界锁内）。 */
    private void doTickOne(long userId) {
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        ReentrantLock lock = board.getLock();
        lock.lock();
        try {
            UserGameState gs = userGameStateMapper.selectOne(
                    new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
            if (gs == null) {
                throw new BizException(ErrorCode.NOT_FOUND);
            }
            MarketState state = MarketState.valueOf(gs.getMarketState());
            if (state != MarketState.MORNING_OPEN && state != MarketState.AFTERNOON_OPEN) {
                return;
            }
            // 心跳门控的时间戳必须"每跳"打一次：放到 persistSnapshot 里就变成每 5 跳才更新，
            // 中间几拍调度器都以为早到点了，会以心跳速度连冲 5 跳，倒计时于是突然掉好几秒。
            board.setLastTickAtNanos(System.nanoTime());
            if (board.getCurrentEra() == null) {
                marketStateService.rederiveDayContext(userId, board);
            }

            int tick = board.getTickIndex();
            Random rng = WorldRandom.of(gs.getWorldSeed(), "tick" + gs.getCurrentDay() + "." + tick);

            VolatilityEngine.TickContext ctx = volEngine.beginTick(rng);
            double marketShock = board.getShocks().consumeMarket();

            double muMkt = board.getCurrentEra().muMkt();
            if (board.isReverseDay()) {
                muMkt = -muMkt;
            }
            Map<String, Double> sectorCoefs = board.getCurrentEra().sectorCoefs();
            Map<Long, Double> returns = new HashMap<>();

            for (Quote q : board.getQuotes().values()) {
                if (q.isHalted()) {
                    continue;
                }
                StockDef def = StockDefs.byId(q.getStockId());
                if (q.getSealed() != SealState.NONE) {
                    // 封板：钉价；推进封板状态机（炸板掷骰）；intraday 补点保持 90 点对齐
                    sealMachine.apply(q, tick, rng, board.getShocks());
                    q.appendTick(q.getCurrent());
                    returns.put(q.getStockId(), 0.0);
                    continue;
                }
                double prev = q.getCurrent();
                double r = volEngine.rawReturn(q, def, muMkt, sectorCoefs, board.getVolRegime(), tick, ctx, rng)
                        + marketShock + board.getShocks().consumeStock(q.getStockId())
                        + sealMachine.magnetDrift(q);
                double newPrice = Math.max(q.limitDownPrice(),
                        Math.min(q.limitUpPrice(), Quote.round2(prev * (1 + r))));
                q.setCurrent(newPrice);
                sealMachine.apply(q, tick, rng, board.getShocks());
                double finalPrice = q.getCurrent();
                q.appendTick(finalPrice);
                volEngine.updateGarch(q, (finalPrice - prev) / prev);
                returns.put(q.getStockId(), (finalPrice - prev) / prev);
            }

            // 指数：非停牌股（含封板 0 收益）按权重平均
            List<StockDef> active = board.getQuotes().values().stream()
                    .filter(q -> !q.isHalted())
                    .map(q -> StockDefs.byId(q.getStockId()))
                    .toList();
            board.appendIndexTick(indexCalculator.nextIndex(board.getIndexValue(), active, returns));

            // 熔断判定（仅上午、熔断日）：指数日内跌幅触及 -7% 当跳提前收盘
            if (board.isCircuitBreakerDay() && state == MarketState.MORNING_OPEN
                    && board.getIndexValue() / board.getPrevIndex() - 1 <= -GameConstants.CIRCUIT_BREAKER_DROP) {
                board.setTickIndex(tick + 1);
                persistSnapshot(userId, board, gs);
                marketStateService.toSettling(userId, MarketState.MORNING_OPEN);
                settlementService.settle(userId);
                return;
            }

            board.setTickIndex(tick + 1);
            eventFactory.maybeRoll(userId, gs, board);
            danmuService.maybePush(userId, board);
            mainScriptService.tick(userId, gs, board);

            // 每跳行情推送（Task 10.2）：本跳已完成实际推进（非早退分支）即下发快照；
            // pushQuotes 内部再按「在线 + 非快进悬挂」过滤，离线/快进期间天然静默
            pushService.pushQuotes(userId, buildSnapshot(tick, board));

            // 盘中担保检查（追保/强平/爆仓，spec §6.4）：爆仓立即返回，不再推进 45/90 边界
            if (marginService.intradayCheck(userId, gs, board)) {
                return;
            }

            if (tick + 1 == GameConstants.TICKS_PER_SESSION && state == MarketState.MORNING_OPEN) {
                persistSnapshot(userId, board, gs);
                marketStateService.toLunchBreak(userId);
            } else if (tick + 1 == GameConstants.TOTAL_TICKS) {
                persistSnapshot(userId, board, gs);
                marketStateService.toSettling(userId, MarketState.AFTERNOON_OPEN);
                settlementService.settle(userId);
            } else if ((tick + 1) % 5 == 0) {
                persistSnapshot(userId, board, gs);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Checkpoint 落库（每 5 跳 + 边界 45/90/熔断）：24 只 user_stock_state 逐行
     *  currentPrice/highPrice/lowPrice = 行情板值（updateById 走 @Version 乐观锁），
     *  game_state.tickIndex/index_value 同步（index_value 同列两用，spec §10：
     *  盘中即当前指数快照，跨日由开盘逻辑重置为昨收）；交易时段字段（open）开盘
     *  已写不再动。豁免：user_game_state 无 @Version，写互斥由世界锁串行化保证
     *  （stock_state 行级乐观锁兜底）。 */
    private void persistSnapshot(long userId, QuoteBoard board, UserGameState gs) {
        List<UserStockState> states = userStockStateMapper.selectList(
                new LambdaQueryWrapper<UserStockState>()
                        .eq(UserStockState::getUserId, userId)
                        .orderByAsc(UserStockState::getStockId));
        for (UserStockState state : states) {
            Quote quote = board.quote(state.getStockId());
            state.setCurrentPrice(BigDecimal.valueOf(quote.getCurrent()));
            state.setHighPrice(BigDecimal.valueOf(quote.getHigh()));
            state.setLowPrice(BigDecimal.valueOf(quote.getLow()));
            userStockStateMapper.updateById(state);
        }
        gs.setTickIndex(board.getTickIndex());
        gs.setIndexValue(board.getIndexValue());
        userGameStateMapper.updateById(gs);
    }

    /** 组装每跳行情推送快照（Task 10.2）：tickIndex、等权指数与 24 只个股的 current/涨跌幅。 */
    private MarketSnapshot buildSnapshot(int tick, QuoteBoard board) {
        List<MarketSnapshot.Row> rows = new ArrayList<>();
        for (Quote q : board.getQuotes().values()) {
            double changePct = q.getPrevClose() > 0 ? (q.getCurrent() - q.getPrevClose()) / q.getPrevClose() : 0.0;
            rows.add(new MarketSnapshot.Row(q.getStockId(), q.getCurrent(), changePct));
        }
        return new MarketSnapshot(tick, board.getIndexValue(), rows);
    }
}
