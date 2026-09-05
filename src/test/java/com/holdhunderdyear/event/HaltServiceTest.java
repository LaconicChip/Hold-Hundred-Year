package com.holdhunderdyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.holdhunderdyear.market.MarketStateService;
import com.holdhunderdyear.market.QuoteBoardRegistry;
import com.holdhunderdyear.market.WorldTickService;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserScriptDay;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserScriptDayMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.world.WorldRandom;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停牌服务集成测试（hold_year_test 库，方法级事务自动回滚）：halt 原语落库、停牌日
 * （tickOne）股价冻结、制裁闪崩 SANCTION → 当日钉跌停并停牌 1~3 天、复牌日 openDay 复牌
 * 且 5%~10% 补跌（妖股 +10% 一字分支 15% 概率，测试自选下杀日的 seed 规避）。复牌补跌
 * 主体为既有 MarketStateService.resumeHaltedStocks，本测试只验证被 SANCTION 停牌后被复用。
 */
@SpringBootTest
@Transactional
class HaltServiceTest {

    private static final long TARGET_STOCK_ID = 5L;
    private static final int SANCTION_DAY = 5;

    @Autowired private HaltService haltService;
    @Autowired private WorldSeeder worldSeeder;
    @Autowired private MarketStateService marketStateService;
    @Autowired private WorldTickService worldTickService;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserStockStateMapper userStockStateMapper;
    @Autowired private UserScriptDayMapper userScriptDayMapper;

    @Test
    void haltPrimitivePersistsFlagAndUntilDay() {
        long userId = newUser();
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        int today = gameState(userId).getCurrentDay();
        haltService.haltForDays(userId, board, TARGET_STOCK_ID, today, 3);

        UserStockState state = stockState(userId, TARGET_STOCK_ID);
        assertEquals(Boolean.TRUE, state.getHalted(), "halted flag must persist");
        assertEquals(today + 3, state.getHaltUntilDay(), "halt_until_day must be currentDay+days");
        assertTrue(quoteBoardRegistry.getOrLoad(userId).quote(TARGET_STOCK_ID).isHalted(),
                "board quote must be halted");
    }

    @Test
    void haltedStockPriceFrozenAcrossTicks() {
        long userId = newUser();
        marketStateService.startDay(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        double before = board.quote(TARGET_STOCK_ID).getCurrent();

        int today = gameState(userId).getCurrentDay();
        haltService.haltForDays(userId, board, TARGET_STOCK_ID, today, 2);

        for (int i = 0; i < 5; i++) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
        }
        assertEquals(before, quoteBoardRegistry.getOrLoad(userId).quote(TARGET_STOCK_ID).getCurrent(), 0.0,
                "halted stock price must not move during trading ticks");
    }

    @Test
    void sanctionHaltsTargetFor1To3DaysAndResumesWithGapDrop() {
        long userId = newUser();
        setWorldSeed(userId, 987654321L);
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(SANCTION_DAY - 1); // CLOSED opens currentDay+1 → 打开 SANCTION 日
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);
        insertSanctionDay(userId, SANCTION_DAY);

        marketStateService.startDay(userId); // opens SANCTION day
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        Quote target = board.quote(TARGET_STOCK_ID);
        assertTrue(target.isHalted(), "SANCTION must halt the target stock");
        UserStockState haltedRow = stockState(userId, TARGET_STOCK_ID);
        assertTrue(haltedRow.getHaltUntilDay() >= SANCTION_DAY + 1
                        && haltedRow.getHaltUntilDay() <= SANCTION_DAY + 3,
                "halt_until_day must be open day + 1..3, got " + haltedRow.getHaltUntilDay());

        double frozen = target.getCurrent();
        for (int i = 0; i < 5; i++) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
        }
        assertEquals(frozen, quoteBoardRegistry.getOrLoad(userId).quote(TARGET_STOCK_ID).getCurrent(), 0.0,
                "halted target must be frozen during SANCTION day");

        // 推进到复牌日：自选一个下杀补跌的 seed 保证 "复牌补跌"（规避 15% 妖股 +10% 一字分支）
        int resumeDay = pickDownGapResumeDay(userId);
        userStockStateMapper.update(null, new UpdateWrapper<UserStockState>()
                .eq("user_id", userId)
                .eq("stock_id", TARGET_STOCK_ID)
                .set("halt_until_day", resumeDay));
        gs = gameState(userId);
        gs.setCurrentDay(resumeDay - 1);
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);

        marketStateService.startDay(userId);
        Quote resumed = quoteBoardRegistry.getOrLoad(userId).quote(TARGET_STOCK_ID);
        assertFalse(resumed.isHalted(), "target must be resumed on the appointed day");
        double drop = resumed.getCurrent() / resumed.getPrevClose() - 1;
        assertTrue(drop < -0.04,
                "resumption must gap-drop relative to pre-halt close: drop=" + drop);
        assertEquals(Boolean.FALSE, stockState(userId, TARGET_STOCK_ID).getHalted(),
                "halted flag must be cleared on resume");
    }

    @Test
    void sanctionDayAlsoSealsTargetLimitDownBeforeHalt() {
        long userId = newUser();
        setWorldSeed(userId, 1123581L);
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(SANCTION_DAY - 1); // CLOSED opens currentDay+1 → 打开 SANCTION 日
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);
        insertSanctionDay(userId, SANCTION_DAY);

        marketStateService.startDay(userId);
        Quote target = quoteBoardRegistry.getOrLoad(userId).quote(TARGET_STOCK_ID);
        assertTrue(target.isHalted(), "SANCTION halt chain must be active");
        assertTrue(Math.abs(target.getCurrent() - target.getPrevClose()) > 0.0001,
                "the sanctioned stock must have been force-sealed (moved off prevClose)");
    }

    // ---- 辅助 ----

    private long newUser() {
        return worldSeeder.seedUser("halt_user" + System.nanoTime(), "$2a$10$bcrypthash");
    }

    private void insertSanctionDay(long userId, int day) {
        UserScriptDay scriptDay = new UserScriptDay();
        scriptDay.setUserId(userId);
        scriptDay.setGameDay(day);
        scriptDay.setScriptType("SANCTION");
        scriptDay.setParamsJson("{\"targetStockId\": " + TARGET_STOCK_ID + "}");
        userScriptDayMapper.insert(scriptDay);
    }

    /** 自选 resume 日：要求 resume{day} 盐的复牌方向为下杀（roll &gt; 0.15 免妖股一字），
     *  保证断言 "复牌补跌" 不因 15% 妖股分支翻绿。seed 相同则结果确定。 */
    private int pickDownGapResumeDay(long userId) {
        long seed = gameState(userId).getWorldSeed();
        for (int day = SANCTION_DAY + 1; day <= SANCTION_DAY + 20; day++) {
            if (WorldRandom.of(seed, "resume" + day).nextDouble() > 0.15) {
                return day;
            }
        }
        return SANCTION_DAY + 2; // 兜底（理论不可达）
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private void setWorldSeed(long userId, long worldSeed) {
        UserGameState state = gameState(userId);
        state.setWorldSeed(worldSeed);
        userGameStateMapper.updateById(state);
    }

    private UserStockState stockState(long userId, long stockId) {
        return userStockStateMapper.selectOne(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId)
                .eq(UserStockState::getStockId, stockId));
    }

    private void handleBoundary(long userId) {
        UserGameState gs = gameState(userId);
        switch (gs.getMarketState()) {
            case "LUNCH_BREAK" -> marketStateService.resumeAfternoon(userId);
            case "CLOSED" -> marketStateService.startDay(userId);
            default -> { }
        }
    }
}