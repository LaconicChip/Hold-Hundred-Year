package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.game.model.enums.EraCode;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.game.model.enums.VolRegime;
import com.holdhunderdyear.market.entity.UserEra;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.engine.SealMachine;
import com.holdhunderdyear.market.mapper.UserEraMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.world.WorldRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

/**
 * 交易日状态机与开盘管线（spec §3）：startDay 是唯一的日入口——
 * NOT_STARTED 开启第 1 日；CLOSED 开启下一交易日（current_day+1、隔夜跳空落地、
 * 纪元切换消息、波动体制掷点）；盘中态幂等返回；午休/结算/终态拒绝。
 * 开盘走 evict-then-reload：缓存板从最新 stock_state 行重建，陈旧分时/冲击自然清空。
 */
@Service
public class MarketStateService {

    private final UserGameStateMapper userGameStateMapper;
    private final UserStockStateMapper userStockStateMapper;
    private final UserMessageMapper userMessageMapper;
    private final UserEraMapper userEraMapper;
    private final QuoteBoardRegistry quoteBoardRegistry;
    private final SealMachine sealMachine = new SealMachine();

    public MarketStateService(UserGameStateMapper userGameStateMapper,
                              UserStockStateMapper userStockStateMapper,
                              UserMessageMapper userMessageMapper,
                              UserEraMapper userEraMapper,
                              QuoteBoardRegistry quoteBoardRegistry) {
        this.userGameStateMapper = userGameStateMapper;
        this.userStockStateMapper = userStockStateMapper;
        this.userMessageMapper = userMessageMapper;
        this.userEraMapper = userEraMapper;
        this.quoteBoardRegistry = quoteBoardRegistry;
    }

    /**
     * 开盘（或进入下一交易日）。返回开盘后的行情板。
     * MORNING_OPEN/AFTERNOON_OPEN：幂等返回当前板；LUNCH_BREAK/SETTLING：NOT_TRADING_TIME；
     * BANKRUPT/FINISHED：FINISHED_STATE；无 game_state：NOT_FOUND。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuoteBoard startDay(long userId) {
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

    /** 开启第 newDay 个交易日：跳空落地（非首日）、清板重载、纪元消息、体制掷点、状态落库。 */
    private QuoteBoard openDay(long userId, UserGameState gameState, int newDay) {
        int prevDay = newDay - 1;

        quoteBoardRegistry.evict(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        // 世界锁协议（P5）：开盘与调度/快进/交易互斥，锁内完成行情板写入。
        board.getLock().lock();
        try {
            if (prevDay > 0) {
                applyOvernightGaps(userId, board, newDay, gameState.getWorldSeed());
            }
            board.getShocks().clear();
            board.setTickIndex(0);
        } finally {
            board.getLock().unlock();
        }

        detectEraSwitchAndNotify(userId, newDay, prevDay);

        gameState.setCurrentDay(newDay);
        gameState.setMarketState(MarketState.MORNING_OPEN.name());
        gameState.setTickIndex(0);
        gameState.setVolRegime(rollVolRegime(gameState.getWorldSeed(), newDay).name());
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

    /** 纪元切换检测：newDay 所在纪元 != prevDay 所在纪元（prevDay=0 视为无纪元，首日常开）时
     *  推送一条 SYSTEM/NOTIFY 消息（占位叙事，文案 Phase 8 替换）。 */
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
        message.setContent("新纪元开启，市场的空气都变了味道。（纪元叙事占位，Phase 8 替换）");
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

    private UserGameState requireGameState(long userId) {
        UserGameState gameState = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        if (gameState == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gameState;
    }
}
