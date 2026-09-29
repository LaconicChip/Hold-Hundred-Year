package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.event.HaltService;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.constants.StockDefs.StockDef;
import com.holdhunderdyear.game.model.enums.EraCode;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.game.model.enums.ScriptType;
import com.holdhunderdyear.game.model.enums.SealState;
import com.holdhunderdyear.game.model.enums.VolRegime;
import com.holdhunderdyear.market.entity.UserEra;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserScriptDay;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.engine.IndexCalculator;
import com.holdhunderdyear.market.engine.PriceShock;
import com.holdhunderdyear.market.engine.SealMachine;
import com.holdhunderdyear.market.mapper.UserEraMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserScriptDayMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.trade.IpoService;
import com.holdhunderdyear.world.EraScheduler;
import com.holdhunderdyear.world.WorldRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 交易日状态机与开盘管线（spec §3）：startDay 是唯一的日入口——
 * NOT_STARTED 开启第 1 日；CLOSED 开启下一交易日（current_day+1、隔夜跳空落地、
 * 纪元切换消息、波动体制掷点）；盘中态幂等返回；午休/结算/终态拒绝。
 * 开盘走 evict-then-reload：缓存板从最新 stock_state 行重建，陈旧分时/冲击自然清空。
 *
 * <p>盘中还提供日内状态转换（toLunchBreak/resumeAfternoon，由 WorldTickService 在
 * 半场边界自动调用）与盘中恢复兜底（rederiveDayContext，restore 后重算当日上下文）。
 *
 * <p>开盘还依次完成（spec §4.3/§4.4）：当日上下文写入（纪元/体制/熔断标记）、
 * 停牌复牌补跌、反向意外日掷点、剧本日强制行为（千股涨停/跌停钉板、救市全市场冲击、
 * 制裁闪崩钉跌停、黑7月按板块钉板；熔断日开盘补强 −6%~−8% 跳空；IPO 开盘无行为）。
 * 此外在 setCurrentEra 之后、状态落库前调用 {@link IpoService#onDayOpen} 处理打新时序
 * （申购通知/中签缴款/上市结算，盐独立于行情）。
 * 全部随机性经 WorldRandom.of(worldSeed, salt)，盐与消费顺序固定（同 seed 同日必得同结果）：
 * <ul>
 *   <li>"gap"+day：隔夜跳空（每股按 stockId 升序经 SealMachine，一字板时消费封板时长 int）；</li>
 *   <li>"resume"+day：复牌补跌（每股按 stockId 升序：路径 double → 补跌幅度 u double? →
 *       妖股一字板时长 int?）；</li>
 *   <li>"reverse"+day：反向意外日（单个 double，p&lt;0.08）；</li>
 *   <li>"script"+day：千股涨停/跌停选股（每条 LIMIT_*_ALL 行一次 Collections.shuffle，
 *       剧本日行按 ScriptType 声明顺序处理）；</li>
 *   <li>"rescue"+day：救市冲击幅度（单个 double）；</li>
 *   <li>"circuit"+day：熔断日开盘补强跳空（每股按声明序一个 double）；</li>
 *   <li>"volregime"+day：体制掷点（单个 double）。</li>
 * </ul>
 */
@Service
public class MarketStateService {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 黑7月崩盘（spec §4.4）：钉跌停的科技板块。 */
    private static final Set<String> CRASH_DOWN_SECTORS = Set.of("半导体", "算力", "通信");

    /** 黑7月崩盘：钉涨停的防御板块。 */
    private static final Set<String> CRASH_UP_SECTORS = Set.of("银行", "煤炭");

    private static final Map<Long, String> SECTOR_BY_STOCK_ID = StockDefs.ALL.stream()
            .collect(Collectors.toUnmodifiableMap(StockDefs.StockDef::id, StockDefs.StockDef::sector));

    private final UserGameStateMapper userGameStateMapper;
    private final UserStockStateMapper userStockStateMapper;
    private final UserMessageMapper userMessageMapper;
    private final UserEraMapper userEraMapper;
    private final UserScriptDayMapper userScriptDayMapper;
    private final QuoteBoardRegistry quoteBoardRegistry;
    private final HaltService haltService;
    private final IpoService ipoService;
    private final SealMachine sealMachine = new SealMachine();
    private final IndexCalculator indexCalculator = new IndexCalculator();
    private final TransactionTemplate transactionTemplate;

    public MarketStateService(UserGameStateMapper userGameStateMapper,
                              UserStockStateMapper userStockStateMapper,
                              UserMessageMapper userMessageMapper,
                              UserEraMapper userEraMapper,
                              UserScriptDayMapper userScriptDayMapper,
                              QuoteBoardRegistry quoteBoardRegistry,
                              HaltService haltService,
                              IpoService ipoService,
                              PlatformTransactionManager transactionManager) {
        this.userGameStateMapper = userGameStateMapper;
        this.userStockStateMapper = userStockStateMapper;
        this.userMessageMapper = userMessageMapper;
        this.userEraMapper = userEraMapper;
        this.userScriptDayMapper = userScriptDayMapper;
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.haltService = haltService;
        this.ipoService = ipoService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 开盘（或进入下一交易日）。返回开盘后的行情板。
     * MORNING_OPEN/AFTERNOON_OPEN：幂等返回当前板；LUNCH_BREAK/SETTLING：NOT_TRADING_TIME；
     * BANKRUPT/FINISHED：FINISHED_STATE；无 game_state：NOT_FOUND。
     *
     * <p>并发协议（P5）：全程持有用户世界锁（registry 层，跨 evict 存活），事务经
     * TransactionTemplate 在锁内开启并提交——后到线程拿到锁时读到的必是已提交状态，
     * 杜绝并发双开（重复跳空/纪元消息）。
     */
    public QuoteBoard startDay(long userId) {
        quoteBoardRegistry.worldLock(userId).lock();
        try {
            return transactionTemplate.execute(status -> doStartDay(userId));
        } finally {
            quoteBoardRegistry.worldLock(userId).unlock();
        }
    }

    /** startDay 事务体（世界锁内开启，提交先于锁释放）。 */
    private QuoteBoard doStartDay(long userId) {
        UserGameState gameState = requireGameState(userId);
        MarketState state = MarketState.valueOf(gameState.getMarketState());
        switch (state) {
            case NOT_STARTED:
                return openDay(userId, gameState, gameState.getCurrentDay());
            case CLOSED:
                return openDay(userId, gameState, gameState.getCurrentDay() + 1);
            case MORNING_OPEN:
            case AFTERNOON_OPEN:
                return quoteBoardRegistry.getOrLoad(userId);
            case LUNCH_BREAK:
            case SETTLING:
                throw new BizException(ErrorCode.NOT_TRADING_TIME);
            case BANKRUPT:
            case FINISHED:
            default:
                throw new BizException(ErrorCode.FINISHED_STATE);
        }
    }

    /** 上午收盘：MORNING_OPEN → LUNCH_BREAK 持久化（tickOne 走满 45 跳自动调用）。
     *  其他源状态抛 MARKET_CLOSED。 */
    @Transactional(rollbackFor = Exception.class)
    public void toLunchBreak(long userId) {
        transitionState(userId, MarketState.MORNING_OPEN, MarketState.LUNCH_BREAK);
    }

    /** 下午开盘：LUNCH_BREAK → AFTERNOON_OPEN 持久化（午休结束消息跳空 Phase 8 接入，
     *  现在直转）。其他源状态抛 MARKET_CLOSED。 */
    @Transactional(rollbackFor = Exception.class)
    public void resumeAfternoon(long userId) {
        transitionState(userId, MarketState.LUNCH_BREAK, MarketState.AFTERNOON_OPEN);
    }

    /** 收盘进入结算态（spec §3：45 跳收盘 AFTERNOON_OPEN → SETTLING，熔断提前收盘
     *  MORNING_OPEN → SETTLING）；源状态不符抛 MARKET_CLOSED。结算体（settle）随后
     *  在同一世界锁事务内落位终态。 */
    @Transactional(rollbackFor = Exception.class)
    public void toSettling(long userId, MarketState expected) {
        transitionState(userId, expected, MarketState.SETTLING);
    }

    /** 状态机转换落库：源状态不匹配抛 MARKET_CLOSED。 */
    private void transitionState(long userId, MarketState expected, MarketState target) {
        UserGameState gameState = requireGameState(userId);
        if (MarketState.valueOf(gameState.getMarketState()) != expected) {
            throw new BizException(ErrorCode.MARKET_CLOSED);
        }
        gameState.setMarketState(target.name());
        userGameStateMapper.updateById(gameState);
    }

    /** 盘中恢复兜底（P9 重启恢复）：全部当日上下文确定性重算，不依赖 DB 已写值——
     *  currentEra=eraInstanceForDay、volRegime=盐 "volregime"+day 重掷、reverseDay=
     *  盐 "reverse"+day 重掷、circuitBreakerDay=当日 script 含 CIRCUIT_BREAKER。
     *  调用方持有世界锁（restore 后 currentEra==null 即触发）。 */
    public void rederiveDayContext(long userId, QuoteBoard board) {
        UserGameState gameState = requireGameState(userId);
        long worldSeed = gameState.getWorldSeed();
        int day = gameState.getCurrentDay();
        board.setCurrentEra(eraInstanceForDay(worldSeed, day));
        board.setVolRegime(rollVolRegime(worldSeed, day));
        board.setReverseDay(WorldRandom.of(worldSeed, "reverse" + day).nextDouble() < 0.08);
        board.setCircuitBreakerDay(scriptDaysFor(userId, day).stream()
                .anyMatch(scriptDay -> ScriptType.CIRCUIT_BREAKER.name().equals(scriptDay.getScriptType())));
    }

    /** 开启第 newDay 个交易日：跳空落地（非首日，先于一切）→ 清板重载 → 当日上下文
     *  （纪元/体制/熔断标记）→ 停牌复牌补跌 → 反向意外日掷点 → 剧本日强制行为
     *  （钉板覆盖跳空落地的价格）→ 纪元消息 → 状态落库。 */
    private QuoteBoard openDay(long userId, UserGameState gameState, int newDay) {
        int prevDay = newDay - 1;
        long worldSeed = gameState.getWorldSeed();

        quoteBoardRegistry.evict(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        // 当日指数基准 = 昨收指数（结算后 index_value 不再变动；首日即基期 1000）。
        // restore 读到的 prev_index 可能是上一日的，这里统一纠正并随后落库，
        // 使盘中重启后的熔断阈值基数仍是当日昨收（spec §10 单列两用的根治）。
        double dayBaseline = gameState.getIndexValue();
        board.setPrevIndex(dayBaseline);

        VolRegime regime = rollVolRegime(worldSeed, newDay);
        List<UserScriptDay> scriptDays = scriptDaysFor(userId, newDay);

        // 世界锁协议（P5）：开盘与调度/快进/交易互斥，锁内完成行情板写入。
        board.getLock().lock();
        try {
            if (prevDay > 0) {
                applyOvernightGaps(userId, board, newDay, worldSeed);
            }
            board.getShocks().clear();
            board.setTickIndex(0);

            board.setCurrentEra(eraInstanceForDay(worldSeed, newDay));
            board.setVolRegime(regime);
            board.setCircuitBreakerDay(scriptDays.stream()
                    .anyMatch(day -> ScriptType.CIRCUIT_BREAKER.name().equals(day.getScriptType())));

            resumeHaltedStocks(userId, board, newDay, worldSeed);
            board.setReverseDay(WorldRandom.of(worldSeed, "reverse" + newDay).nextDouble() < 0.08);
            applyScriptDayBehaviors(userId, board, scriptDays, newDay, worldSeed);
            ipoService.onDayOpen(userId, gameState, board, newDay);
            // 所有开盘期价格变动（隔夜跳空→复牌补跌→剧本钉板→熔断补强）落地后，
            // 统一重算一次开盘指数（spec §4.2：指数按未停牌股票的平均涨跌幅更新）
            recalculateOpeningIndex(board);
        } finally {
            board.getLock().unlock();
        }

        detectEraSwitchAndNotify(userId, newDay, prevDay);

        gameState.setCurrentDay(newDay);
        gameState.setMarketState(MarketState.MORNING_OPEN.name());
        gameState.setTickIndex(0);
        gameState.setVolRegime(regime.name());
        gameState.setPrevIndex(dayBaseline);
        userGameStateMapper.updateById(gameState);
        return board;
    }

    /** 隔夜跳空落地：每股按 user_stock_state.gap_pct 经 SealMachine.applyOpenGap 写入行情板，
     *  并把 open/high/low/current 回写 stock_state、gap_pct 清零；停牌股价格冻结、gap 保留。 */
    private void applyOvernightGaps(long userId, QuoteBoard board, int newDay, long worldSeed) {
        Random rng = WorldRandom.of(worldSeed, "gap" + newDay);
        List<UserStockState> states = userStockStateMapper.selectList(
                new LambdaQueryWrapper<UserStockState>()
                        .eq(UserStockState::getUserId, userId)
                        .orderByAsc(UserStockState::getStockId));
        for (UserStockState state : states) {
            if (Boolean.TRUE.equals(state.getHalted())) {
                continue;
            }
            Quote quote = board.quote(state.getStockId());
            double gapPct = state.getGapPct() != null ? state.getGapPct() : 0.0;
            sealMachine.applyOpenGap(quote, gapPct, rng);
            state.setOpenPrice(BigDecimal.valueOf(quote.getOpen()));
            state.setHighPrice(BigDecimal.valueOf(quote.getHigh()));
            state.setLowPrice(BigDecimal.valueOf(quote.getLow()));
            state.setCurrentPrice(BigDecimal.valueOf(quote.getCurrent()));
            state.setGapPct(0.0);
            userStockStateMapper.updateById(state);
        }
    }

    /** 停牌复牌补跌（spec §4.4）：halt_until_day 到期的停牌股当日复牌，rng salt "resume"+day，
     *  每股（stockId 升序）先掷路径 double——<0.15 妖股出关（+10% 一字涨停），否则补跌
     *  −(5%+5%·u)——再经 SealMachine.applyOpenGap 落地（妖股路径额外消费封板时长 int）。
     *  halted=false、halt_until_day=null、limit_up_streak=0、陈旧 gap_pct 一并清零
     *  （UpdateWrapper 显式 set null，updateById 会忽略 null 字段）。
     *  未到期或无限期停牌股保持冻结：无跳空、无复牌、价格不动。 */
    private void resumeHaltedStocks(long userId, QuoteBoard board, int newDay, long worldSeed) {
        Random rng = WorldRandom.of(worldSeed, "resume" + newDay);
        List<UserStockState> states = userStockStateMapper.selectList(
                new LambdaQueryWrapper<UserStockState>()
                        .eq(UserStockState::getUserId, userId)
                        .orderByAsc(UserStockState::getStockId));
        for (UserStockState state : states) {
            if (!Boolean.TRUE.equals(state.getHalted())
                    || state.getHaltUntilDay() == null || state.getHaltUntilDay() > newDay) {
                continue;
            }
            Quote quote = board.quote(state.getStockId());
            quote.setHalted(false);
            quote.setLimitUpStreak(0);
            double roll = rng.nextDouble();
            double resumeGap = roll < 0.15 ? 0.10 : -(0.05 + 0.05 * rng.nextDouble());
            sealMachine.applyOpenGap(quote, resumeGap, rng);
            userStockStateMapper.update(null, new UpdateWrapper<UserStockState>()
                    .eq("user_id", userId)
                    .eq("stock_id", state.getStockId())
                    .set("halted", false)
                    .set("halt_until_day", null)
                    .set("open_price", BigDecimal.valueOf(quote.getOpen()))
                    .set("high_price", BigDecimal.valueOf(quote.getHigh()))
                    .set("low_price", BigDecimal.valueOf(quote.getLow()))
                    .set("current_price", BigDecimal.valueOf(quote.getCurrent()))
                    .set("limit_up_streak", 0)
                    .set("gap_pct", 0.0));
        }
    }

    /** 剧本日强制行为（spec §4.4）：千股涨停/跌停按 sealRatio 洗牌选股钉板（rng salt
     *  "script"+day，停牌股排除）；救市日全市场 +2%~4% ONCE 冲击入队（salt "rescue"+day）；
     *  制裁闪崩把 params.targetStockId 钉死跌停；黑7月按板块钉板（半导体/算力/通信 跌停、
     *  银行/煤炭 涨停）；熔断/IPO 开盘无行为。强制钉板覆盖跳空落地的价格，
     *  current_price 回写 stock_state 保持库内一致。 */
    private void applyScriptDayBehaviors(long userId, QuoteBoard board, List<UserScriptDay> scriptDays,
                                         int newDay, long worldSeed) {
        if (scriptDays.isEmpty()) {
            return;
        }
        Random shuffleRng = WorldRandom.of(worldSeed, "script" + newDay);
        for (UserScriptDay scriptDay : scriptDays) {
            ScriptType type = ScriptType.valueOf(scriptDay.getScriptType());
            JsonNode params = MAPPER.readTree(scriptDay.getParamsJson());
            switch (type) {
                case LIMIT_UP_ALL -> sealAllDirection(userId, board,
                        params.get("sealRatio").asDouble(), SealState.UP, shuffleRng);
                case LIMIT_DOWN_ALL -> sealAllDirection(userId, board,
                        params.get("sealRatio").asDouble(), SealState.DOWN, shuffleRng);
                case RESCUE -> board.getShocks().enqueue(new PriceShock(0L, true,
                        0.02 + 0.02 * WorldRandom.of(worldSeed, "rescue" + newDay).nextDouble(),
                        1, PriceShock.Decay.ONCE));
                case SANCTION -> {
                    long ts = params.get("targetStockId").asLong();
                    forceSealStock(userId, board, ts, SealState.DOWN);
                    // 制裁闪崩 → 当日在跌停封死并停牌 1~3 天 → 复牌补跌（盐 "halt"+day 单个 int 确定性）
                    int days = 1 + WorldRandom.of(worldSeed, "halt" + newDay).nextInt(3);
                    haltService.haltForDays(userId, board, ts, newDay, days);
                }
                case CRASH_WEEK -> sealCrashSectors(userId, board);
                case CIRCUIT_BREAKER -> applyCircuitBreakerOpen(userId, board, newDay, worldSeed);
                case IPO -> {
                    // IPO 由打新流程处理（Phase 9.2）。
                }
            }
        }
    }

    /** 熔断日开盘补强（spec §4.4 上午开盘指数即重挫）：除 circuitBreakerDay 标记外，
     *  对每只非停牌且未封板的股票再落地开盘跳空 −(0.06+0.02·u)（rng 盐 "circuit"+day
     *  每股一个 double，覆盖原隔夜跳空落地的价格，clamp 到跌停价）并回写 stock_state。
     *  指数不在此处更新——由 openDay 尾部的 {@link #recalculateOpeningIndex} 统一重算，
     *  避免跳空均值 −7% 恰为熔断阈值的口径被双计。 */
    private void applyCircuitBreakerOpen(long userId, QuoteBoard board, int newDay, long worldSeed) {
        Random rng = WorldRandom.of(worldSeed, "circuit" + newDay);
        for (Quote quote : board.getQuotes().values()) {
            if (quote.isHalted() || quote.getSealed() != SealState.NONE) {
                continue;
            }
            double gap = -(0.06 + 0.02 * rng.nextDouble());
            sealMachine.applyOpenGap(quote, gap, rng);
            userStockStateMapper.update(null, new UpdateWrapper<UserStockState>()
                    .eq("user_id", userId)
                    .eq("stock_id", quote.getStockId())
                    .set("open_price", BigDecimal.valueOf(quote.getOpen()))
                    .set("high_price", BigDecimal.valueOf(quote.getHigh()))
                    .set("low_price", BigDecimal.valueOf(quote.getLow()))
                    .set("current_price", BigDecimal.valueOf(quote.getCurrent())));
        }
    }

    /** 统一重算开盘指数：昨收 × (1 + 未停牌股等权平均开盘收益)，开盘收益 =
     *  (开盘价 − prevClose)/prevClose（钉板股用钉板价、停牌股剔除），复用
     *  IndexCalculator。纯计算、不消费随机数，不破坏同 seed 同结果确定性——
     *  隔夜跳空/复牌补跌/剧本钉板/熔断补强等所有开盘期价格变动都在此一次性入指数。 */
    private void recalculateOpeningIndex(QuoteBoard board) {
        Map<Long, Double> openReturns = new HashMap<>();
        for (Quote quote : board.getQuotes().values()) {
            if (quote.isHalted()) {
                continue;
            }
            double prevClose = quote.getPrevClose();
            openReturns.put(quote.getStockId(),
                    prevClose > 0 ? (quote.getCurrent() - prevClose) / prevClose : 0.0);
        }
        List<StockDef> active = board.getQuotes().values().stream()
                .filter(q -> !q.isHalted())
                .map(q -> StockDefs.byId(q.getStockId()))
                .toList();
        board.setIndexValue(indexCalculator.nextIndex(board.getPrevIndex(), active, openReturns));
    }

    /** 千股涨停/跌停：非停牌股洗牌后取前 round(24×sealRatio) 只钉板。 */
    private void sealAllDirection(long userId, QuoteBoard board, double sealRatio,
                                  SealState direction, Random rng) {
        List<Long> candidates = new ArrayList<>();
        for (Quote quote : board.getQuotes().values()) {
            if (!quote.isHalted()) {
                candidates.add(quote.getStockId());
            }
        }
        Collections.shuffle(candidates, rng);
        int target = (int) Math.round(board.getQuotes().size() * sealRatio);
        int sealedCount = 0;
        for (Long stockId : candidates) {
            if (sealedCount >= target) {
                break;
            }
            forceSealStock(userId, board, stockId, direction);
            sealedCount++;
        }
    }

    /** 黑7月崩盘：半导体/算力/通信全部钉跌停，银行/煤炭全部钉涨停（停牌股除外）。 */
    private void sealCrashSectors(long userId, QuoteBoard board) {
        for (Quote quote : board.getQuotes().values()) {
            String sector = SECTOR_BY_STOCK_ID.get(quote.getStockId());
            if (CRASH_DOWN_SECTORS.contains(sector)) {
                forceSealStock(userId, board, quote.getStockId(), SealState.DOWN);
            } else if (CRASH_UP_SECTORS.contains(sector)) {
                forceSealStock(userId, board, quote.getStockId(), SealState.UP);
            }
        }
    }

    /** 强制钉板：sealed=direction、current=对应涨跌停价、sealTicksLeft=TOTAL_TICKS（封至收盘），
     *  并回写 stock_state.current_price；停牌股冻结跳过。 */
    private void forceSealStock(long userId, QuoteBoard board, long stockId, SealState direction) {
        Quote quote = board.quote(stockId);
        if (quote.isHalted()) {
            return;
        }
        quote.setSealed(direction);
        quote.setCurrent(direction == SealState.UP ? quote.limitUpPrice() : quote.limitDownPrice());
        quote.setSealTicksLeft(GameConstants.TOTAL_TICKS);
        userStockStateMapper.update(null, new UpdateWrapper<UserStockState>()
                .eq("user_id", userId)
                .eq("stock_id", stockId)
                .set("current_price", BigDecimal.valueOf(quote.getCurrent())));
    }

    /** 查询 day 的剧本日行，按 ScriptType 声明顺序排序（与 ScriptDayScheduler 输出序一致），
     *  保证同日多剧本时随机消费顺序确定。 */
    private List<UserScriptDay> scriptDaysFor(long userId, int day) {
        List<UserScriptDay> rows = userScriptDayMapper.selectList(
                new LambdaQueryWrapper<UserScriptDay>()
                        .eq(UserScriptDay::getUserId, userId)
                        .eq(UserScriptDay::getGameDay, day));
        rows.sort(Comparator.comparingInt(row -> ScriptType.valueOf(row.getScriptType()).ordinal()));
        return rows;
    }

    /** day 所属纪元（EraScheduler 按 worldSeed 重掷——与 WorldSeeder 播种的 user_era 行
     *  同 seed 同结果，11 个纪元恰好铺满 1..108）。 */
    private EraScheduler.EraInstance eraInstanceForDay(long worldSeed, int day) {
        for (EraScheduler.EraInstance era : new EraScheduler().roll(worldSeed)) {
            if (era.startDay() <= day && day <= era.endDay()) {
                return era;
            }
        }
        return null;
    }

    /** 纪元切换检测：newDay 所在纪元 != prevDay 所在纪元（prevDay=0 视为无纪元，首日常开）时
     *  推送一条 SYSTEM/NOTIFY 消息，正文取 {@link EraCode#getNarrative()} 的纪元叙事。 */
    private void detectEraSwitchAndNotify(long userId, int newDay, int prevDay) {
        UserEra current = eraForDay(userId, newDay);
        if (current == null) {
            return;
        }
        UserEra previous = prevDay > 0 ? eraForDay(userId, prevDay) : null;
        if (previous != null && previous.getEraIndex().equals(current.getEraIndex())) {
            return;
        }
        EraCode code = EraCode.valueOf(current.getEraCode());
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource("SYSTEM");
        message.setType("NOTIFY");
        message.setTitle("纪元切换：" + code.getName() + "（" + code.name() + "）");
        message.setContent(code.getNarrative());
        message.setGameDay(newDay);
        message.setIsRead(false);
        userMessageMapper.insert(message);
    }

    /** 查询 day 所属纪元（user_era 行 start_day <= day <= end_day）。 */
    private UserEra eraForDay(long userId, int day) {
        List<UserEra> eras = userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                .eq(UserEra::getUserId, userId)
                .orderByAsc(UserEra::getEraIndex));
        for (UserEra era : eras) {
            if (era.getStartDay() <= day && day <= era.getEndDay()) {
                return era;
            }
        }
        return null;
    }

    /** 波动体制掷点（spec §4.3 三档）：WorldRandom.of(seed, "volregime"+day)，
     *  p<0.2 → CALM、p<0.8 → NORMAL、否则 WILD；同 seed 同日结果确定。 */
    private VolRegime rollVolRegime(long worldSeed, int currentDay) {
        Random rng = WorldRandom.of(worldSeed, "volregime" + currentDay);
        double p = rng.nextDouble();
        if (p < 0.2) {
            return VolRegime.CALM;
        }
        if (p < 0.8) {
            return VolRegime.NORMAL;
        }
        return VolRegime.WILD;
    }

    /** 取 game_state，无则抛 NOT_FOUND：本类与同包调度/快进共用的状态读取入口。 */
    UserGameState requireGameState(long userId) {
        UserGameState gameState = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        if (gameState == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gameState;
    }
}
