package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.model.enums.SealState;
import com.holdhunderdyear.game.model.enums.VolRegime;
import com.holdhunderdyear.market.entity.UserEra;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.UserEraMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.world.WorldRandom;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开盘管线集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖：状态机转换、幂等开盘、非法转换、隔夜跳空落地（含一字板/停牌冻结）、
 * 纪元切换消息、波动体制掷点的确定性。
 */
@SpringBootTest
@Transactional
class MarketStateServiceTest {

    @Autowired
    private WorldSeeder worldSeeder;

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

    @Test
    void startDayFromNotStartedOpensMorningSession() {
        long userId = worldSeeder.seedUser("mkt_open1", "$2a$10$bcrypthash");

        QuoteBoard board = marketStateService.startDay(userId);

        UserGameState state = gameState(userId);
        assertEquals("MORNING_OPEN", state.getMarketState());
        assertEquals(1, state.getCurrentDay());
        assertEquals(0, state.getTickIndex());
        assertEquals(24, board.getQuotes().size());
        assertEquals(0, board.getTickIndex());
        assertEquals(0, board.getShocks().activeCount());
    }

    @Test
    void startDayInMorningOpenIsIdempotentNoOp() {
        long userId = worldSeeder.seedUser("mkt_idle1", "$2a$10$bcrypthash");
        QuoteBoard first = marketStateService.startDay(userId);
        assertEquals(1, messages(userId).size(), "era-1 start message expected");

        UserGameState state = gameState(userId);
        state.setTickIndex(5);
        userGameStateMapper.updateById(state);

        QuoteBoard second = marketStateService.startDay(userId);

        assertSame(first, second, "idempotent path must return the cached board");
        UserGameState after = gameState(userId);
        assertEquals("MORNING_OPEN", after.getMarketState());
        assertEquals(1, after.getCurrentDay(), "currentDay must not advance");
        assertEquals(5, after.getTickIndex(), "tickIndex must not be reset");
        assertEquals(1, messages(userId).size(), "no duplicate era message");
    }

    @Test
    void startDayFromLunchBreakThrowsNotTradingTime() {
        long userId = worldSeeder.seedUser("mkt_lunch1", "$2a$10$bcrypthash");
        setMarketState(userId, "LUNCH_BREAK");

        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(userId));
        assertEquals(ErrorCode.NOT_TRADING_TIME, ex.getErrorCode());
    }

    @Test
    void startDayFromSettlingThrowsNotTradingTime() {
        long userId = worldSeeder.seedUser("mkt_settle1", "$2a$10$bcrypthash");
        setMarketState(userId, "SETTLING");

        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(userId));
        assertEquals(ErrorCode.NOT_TRADING_TIME, ex.getErrorCode());
    }

    @Test
    void startDayFromFinishedThrowsFinishedState() {
        long userId = worldSeeder.seedUser("mkt_fin1", "$2a$10$bcrypthash");
        setMarketState(userId, "FINISHED");

        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(userId));
        assertEquals(ErrorCode.FINISHED_STATE, ex.getErrorCode());
    }

    @Test
    void startDayFromBankruptThrowsFinishedState() {
        long userId = worldSeeder.seedUser("mkt_bank1", "$2a$10$bcrypthash");
        setMarketState(userId, "BANKRUPT");

        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(userId));
        assertEquals(ErrorCode.FINISHED_STATE, ex.getErrorCode());
    }

    @Test
    void startDayForUnknownUserThrowsNotFound() {
        BizException ex = assertThrows(BizException.class, () -> marketStateService.startDay(999999L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void startDayFromClosedAppliesOvernightGapsAndSealsOneWordBoard() {
        long userId = worldSeeder.seedUser("mkt_gap1", "$2a$10$bcrypthash");
        QuoteBoard day1 = marketStateService.startDay(userId);
        // 模拟昨日盘中遗留：缓存板上的陈旧冲击与 tick 进度，开盘后必须被清掉
        day1.setTickIndex(7);

        setMarketState(userId, "CLOSED");
        setGap(userId, 1L, 0.05);
        setGap(userId, 2L, 0.10);
        UserStockState haltedRow = stockState(userId, 3L);
        haltedRow.setHalted(true);
        haltedRow.setGapPct(0.05);
        userStockStateMapper.updateById(haltedRow);

        QuoteBoard board = marketStateService.startDay(userId);

        UserGameState state = gameState(userId);
        assertEquals("MORNING_OPEN", state.getMarketState());
        assertEquals(2, state.getCurrentDay(), "CLOSED -> startDay must advance to next trading day");
        assertEquals(0, state.getTickIndex());
        assertEquals(expectedVolRegime(state.getWorldSeed(), 2).name(), state.getVolRegime());

        assertEquals(0, board.getTickIndex(), "board tickIndex must reset on re-open");
        assertEquals(0, board.getShocks().activeCount(), "stale shocks must be cleared on re-open");
        assertSame(board, quoteBoardRegistry.getOrLoad(userId), "registry must cache the opened board");
        assertEquals(1, messages(userId).size(), "day 2 stays in era 1, no new era message");

        Quote gapped = board.quote(1L);
        double expected = Quote.round2(gapped.getPrevClose() * 1.05);
        assertEquals(expected, gapped.getCurrent(), 1e-9);
        assertEquals(expected, gapped.getOpen(), 1e-9);
        assertEquals(expected, gapped.getHigh(), 1e-9);
        assertEquals(expected, gapped.getLow(), 1e-9);
        assertEquals(SealState.NONE, gapped.getSealed());

        Quote oneWord = board.quote(2L);
        assertEquals(Quote.round2(oneWord.getPrevClose() * 1.10), oneWord.getCurrent(), 1e-9);
        assertEquals(SealState.UP, oneWord.getSealed(), "|gap|>=0.10 must seal first tick");
        assertTrue(oneWord.getSealTicksLeft() >= GameConstants.SEAL_MIN_TICKS);

        Quote frozen = board.quote(3L);
        assertEquals(120.0, frozen.getCurrent(), 1e-9, "halted stock price must stay frozen");
        assertEquals(SealState.NONE, frozen.getSealed());

        UserStockState row = stockState(userId, 1L);
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(row.getCurrentPrice()), "currentPrice");
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(row.getOpenPrice()), "openPrice");
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(row.getHighPrice()), "highPrice");
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(row.getLowPrice()), "lowPrice");
        assertEquals(0.0, row.getGapPct(), 0.0, "applied gap must be consumed");

        UserStockState frozenRow = stockState(userId, 3L);
        assertEquals(0.05, frozenRow.getGapPct(), 0.0, "halted stock gap must be preserved for later");
    }

    @Test
    void startDayOnDayOnePushesEraStartNotifyMessage() {
        long userId = worldSeeder.seedUser("mkt_era1", "$2a$10$bcrypthash");

        marketStateService.startDay(userId);

        List<UserMessage> msgs = messages(userId);
        assertEquals(1, msgs.size());
        UserMessage msg = msgs.get(0);
        assertEquals("SYSTEM", msg.getSource());
        assertEquals("NOTIFY", msg.getType());
        assertEquals(1, msg.getGameDay());
        assertFalse(msg.getIsRead());
        assertNull(msg.getTruthStatus());
        assertTrue(msg.getTitle().contains("BULL_START"), "title must contain era code, title=" + msg.getTitle());
        assertNotNull(msg.getContent());
        assertFalse(msg.getContent().isEmpty());
    }

    @Test
    void startDayIntoNewEraPushesEraSwitchNotifyMessage() {
        long userId = worldSeeder.seedUser("mkt_era2", "$2a$10$bcrypthash");
        UserEra era2 = userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                        .eq(UserEra::getUserId, userId)
                        .orderByAsc(UserEra::getEraIndex))
                .get(1);

        UserGameState state = gameState(userId);
        state.setCurrentDay(era2.getStartDay() - 1);
        state.setMarketState("CLOSED");
        userGameStateMapper.updateById(state);

        marketStateService.startDay(userId);

        assertEquals(era2.getStartDay(), gameState(userId).getCurrentDay());
        List<UserMessage> msgs = messages(userId);
        assertEquals(1, msgs.size());
        UserMessage msg = msgs.get(0);
        assertEquals(era2.getStartDay(), msg.getGameDay());
        assertEquals("SYSTEM", msg.getSource());
        assertEquals("NOTIFY", msg.getType());
        assertTrue(msg.getTitle().contains(era2.getEraCode()), "title=" + msg.getTitle());
    }

    @Test
    void startDayRollsSeededDeterministicVolRegime() {
        long userId = worldSeeder.seedUser("mkt_vol1", "$2a$10$bcrypthash");

        marketStateService.startDay(userId);

        UserGameState state = gameState(userId);
        String regime = state.getVolRegime();
        assertTrue(regime.equals("CALM") || regime.equals("NORMAL") || regime.equals("WILD"),
                "vol_regime must be one of CALM/NORMAL/WILD, got " + regime);
        assertEquals(expectedVolRegime(state.getWorldSeed(), 1).name(), regime,
                "same seed must roll the same regime");
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private void setMarketState(long userId, String marketState) {
        UserGameState state = gameState(userId);
        state.setMarketState(marketState);
        userGameStateMapper.updateById(state);
    }

    private UserStockState stockState(long userId, long stockId) {
        return userStockStateMapper.selectOne(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId)
                .eq(UserStockState::getStockId, stockId));
    }

    private void setGap(long userId, long stockId, double gapPct) {
        UserStockState state = stockState(userId, stockId);
        state.setGapPct(gapPct);
        userStockStateMapper.updateById(state);
    }

    private List<UserMessage> messages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
    }

    /** 与实现保持同一掷点协议：WorldRandom.of(seed, "volregime"+day)，p<0.2 CALM、p<0.8 NORMAL、否则 WILD。 */
    private static VolRegime expectedVolRegime(long worldSeed, int currentDay) {
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
}
