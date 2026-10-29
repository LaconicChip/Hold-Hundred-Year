package com.holdhundredyear.trade;

import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.game.model.enums.EraCode;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserIpoRecord;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserIpoRecordMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.world.EraScheduler.EraInstance;
import com.holdhundredyear.world.WorldRandom;
import com.holdhundredyear.world.WorldSeeder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 打新服务集成测试（hold_year_test 库，方法级事务自动回滚）：覆盖 spec §6.5 / 交接 §5
 * Task 9.2 的新股申购（打新）全流程——
 * <ul>
 * <li>纯函数带：winRate / rollBoards / breaksIssue 各纪元取值区间；</li>
 * <li>顶格申购幂等：同日重复订阅只落一条，非申购日/非本用户/非 IPO 剧本拒绝；</li>
 * <li>中签缴款：SUBSCRIBED 记录在 D+2 被摇号落终态，资金不足不可能 PAID；</li>
 * <li>资金守恒：缴款日扣 issuePrice×1000，上市日 + sellPrice×1000，profit == 结算后余额增量；</li>
 * <li>破发：熊市且连板数 0 时按发行价 ×0.9 结算、profit 负；</li>
 * <li>三纪元连板分布：疯牛 6~12、结构牛 3~6、熊市 0~2；</li>
 * <li>打新零行情：跑满申购→中签→上市全流程后 user_stock_state 仍恒为 24 行。</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class IpoServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long DEFAULT_SEED = 20260904L;

    private static final Set<String> TERMINAL_LOTTERY = Set.of("PAID", "LOST", "ABANDONED");

    @Autowired private IpoService ipoService;
    @Autowired private WorldSeeder worldSeeder;
    @Autowired private MarketStateService marketStateService;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private UserScriptDayMapper userScriptDayMapper;
    @Autowired private UserIpoRecordMapper userIpoRecordMapper;
    @Autowired private UserStockStateMapper userStockStateMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private UserMessageMapper userMessageMapper;

    @MockitoBean
    private com.holdhundredyear.ws.PushService pushService;

    // ---- 打新通知接线（spec §9 notifications 通道 / §12.1 申购弹窗）----

    @Test
    @SuppressWarnings("unchecked")
    void subscribeDayPushesStructuredIpoNotification() {
        long userId = worldSeeder.seedUser("ipo_notify1", "$2a$10$bcrypthash");
        UserScriptDay ipoDay = userScriptDayMapper.selectList(
                        new LambdaQueryWrapper<UserScriptDay>()
                                .eq(UserScriptDay::getUserId, userId)
                                .eq(UserScriptDay::getScriptType, "IPO")
                                .orderByAsc(UserScriptDay::getGameDay))
                .get(0);

        // 前一日收盘态，开盘进入申购日触发 onDayOpen
        UserGameState state = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        state.setCurrentDay(ipoDay.getGameDay() - 1);
        state.setMarketState("CLOSED");
        userGameStateMapper.updateById(state);

        marketStateService.startDay(userId);

        verify(pushService, atLeastOnce()).pushNotification(eq(userId), argThat(n -> {
            Map<String, Object> notice = (Map<String, Object>) n;
            return notice != null
                    && Long.valueOf(ipoDay.getId()).equals(notice.get("scriptDayId"))
                    && notice.get("ipoName") != null
                    && ((Number) notice.get("issuePrice")).doubleValue() > 0;
        }));
    }

    // ---- 纯函数带 ----

    @Test
    void winRateValuesMatchSpec() {
        assertEquals(0.22, IpoService.winRate(EraCode.EUPHORIA), 1e-9);
        assertEquals(0.28, IpoService.winRate(EraCode.NEW_ENERGY), 1e-9);
        assertEquals(0.28, IpoService.winRate(EraCode.AI_BULL), 1e-9);
        for (EraCode bear : List.of(EraCode.CRASH, EraCode.TRADE_WAR, EraCode.BLACK_JULY, EraCode.CIRCUIT_BREAKER)) {
            assertEquals(0.35, IpoService.winRate(bear), 1e-9, "win rate of " + bear);
        }
        for (EraCode other : List.of(EraCode.BULL_START, EraCode.POST_CRASH, EraCode.BLUE_CHIP, EraCode.REBALANCE)) {
            assertEquals(0.30, IpoService.winRate(other), 1e-9, "win rate of " + other);
        }
    }

    @Test
    void rollBoardsStayInEraBand() {
        Random rng = new Random(42L);
        assertBoardsIn(EraCode.EUPHORIA, rng, 6, 12, 500, "疯牛 6~12");
        assertBoardsIn(EraCode.NEW_ENERGY, rng, 3, 6, 500, "结构牛新能源 3~6");
        assertBoardsIn(EraCode.AI_BULL, rng, 3, 6, 500, "结构牛AI 3~6");
        for (EraCode bear : List.of(EraCode.CRASH, EraCode.TRADE_WAR, EraCode.BLACK_JULY, EraCode.CIRCUIT_BREAKER)) {
            assertBoardsIn(bear, rng, 0, 2, 500, "熊市 0~2");
        }
        for (EraCode other : List.of(EraCode.BULL_START, EraCode.POST_CRASH, EraCode.BLUE_CHIP, EraCode.REBALANCE)) {
            assertBoardsIn(other, rng, 0, 4, 500, "普涨/震荡 0~4");
        }
    }

    @Test
    void breaksIssueOnlyInBearWithZeroBoards() {
        assertFalse(IpoService.breaksIssue(EraCode.BULL_START, 0), "non-bear must not break at 0 boards");
        assertFalse(IpoService.breaksIssue(EraCode.EUPHORIA, 0), "bull must not break");
        assertTrue(IpoService.breaksIssue(EraCode.CRASH, 0), "bear with 0 boards must break");
        assertTrue(IpoService.breaksIssue(EraCode.TRADE_WAR, 0), "bear with 0 boards must break");
        assertTrue(IpoService.breaksIssue(EraCode.BLACK_JULY, 0), "bear with 0 boards must break");
        assertTrue(IpoService.breaksIssue(EraCode.CIRCUIT_BREAKER, 0), "bear with 0 boards must break");
        assertFalse(IpoService.breaksIssue(EraCode.CRASH, 1), "bear with 1 board must not break");
    }

    // ---- 申购幂等 / 订阅校验 ----

    @Test
    void subscribeIsIdempotentAndStoresIpoInfo() {
        long userId = seedInMarket();
        UserScriptDay sd = insertIpo(userId, 1, "雷霆安防", 10.0);

        UserIpoRecord first = ipoService.subscribe(userId, sd.getId());
        UserIpoRecord second = ipoService.subscribe(userId, sd.getId());

        assertEquals(first.getId(), second.getId(), "repeat subscribe must return the existing record");
        List<UserIpoRecord> rows = ipoRecordMapper(userId, sd.getId());
        assertEquals(1, rows.size(), "only a single subscription row may exist");
        assertEquals("SUBSCRIBED", rows.get(0).getStatus());
        assertEquals(1, rows.get(0).getGameDay(), "subscription keeps the IPO subscription day D");
        assertEquals("雷霆安防", rows.get(0).getIpoName());
        assertEquals(0, asDecimal("10.00").compareTo(rows.get(0).getIssuePrice()));
    }

    @Test
    void subscribeRejectedOffSubscriptionDayOrForWrongScriptDay() {
        long userId = seedInMarket();
        UserScriptDay future = insertIpo(userId, 99, "雷霆安防", 10.0);
        BizException expired = assertThrows(BizException.class,
                () -> ipoService.subscribe(userId, future.getId()),
                "subscribing a non-today IPO script day must be rejected");
        assertEquals(ErrorCode.IPO_EXPIRED, expired.getErrorCode(),
                "过期申购要给一句人话，而不是笼统的请求参数错误");

        UserScriptDay notIpo = insertScriptDay(userId, 1, "LIMIT_UP_ALL", Map.of("sealRatio", 0.90));
        assertThrows(BizException.class, () -> ipoService.subscribe(userId, notIpo.getId()),
                "subscribing a non-IPO script type must be rejected");

        long other = seedInMarket();
        UserScriptDay their = insertIpo(other, 1, "凤凰光学", 12.0);
        assertThrows(BizException.class, () -> ipoService.subscribe(userId, their.getId()),
                "subscribing another user's script day must be rejected");
    }

    @Test
    void subscribeDayPushesSubscriptionNotification() {
        long userId = seedInMarket();
        UserScriptDay sd = insertIpo(userId, 1, "雷霆安防", 10.0);

        ipoService.onDayOpen(userId, gameState(userId), board(userId), 1);

        UserMessage note = notificationContaining(userId, "新股今日申购");
        assertNotNull(note, "the IPO day must push a subscription notice");
        assertTrue(note.getContent().contains("雷霆安防"), "notice must name the stock");
        assertTrue(note.getContent().contains("10.00"), "notice must state the issue price");
        assertEquals(0, ipoRecordMapper(userId, sd.getId()).size(),
                "opening the subscription day alone must not auto-create a record");
    }

    // ---- 摇号区间 ----

    @Test
    void lotteryTransitionsSubscribedToTerminalStatus() {
        long userId = seedInMarket();
        forceEra(userId, EraCode.BULL_START);
        UserScriptDay sd = insertIpo(userId, 1, "雷霆安防", 10.0);
        UserIpoRecord rec = ipoService.subscribe(userId, sd.getId());
        setCurrentDay(userId, 3);

        ipoService.onDayOpen(userId, gameState(userId), board(userId), 3);

        UserIpoRecord after = userIpoRecordMapper.selectById(rec.getId());
        assertTrue(TERMINAL_LOTTERY.contains(after.getStatus()),
                "lottery must land on PAID/LOST/ABANDONED, got " + after.getStatus());
    }

    @Test
    void insufficientCashNeverBecomesPaid() {
        long userId = seedInMarket();
        forceEra(userId, EraCode.BULL_START);
        UserScriptDay sd = insertIpo(userId, 1, "雷霆安防", 10.0);
        UserIpoRecord rec = ipoService.subscribe(userId, sd.getId());
        setBalance(userId, BigDecimal.ZERO);
        setCurrentDay(userId, 3);

        ipoService.onDayOpen(userId, gameState(userId), board(userId), 3);

        String status = userIpoRecordMapper.selectById(rec.getId()).getStatus();
        assertNotEquals("PAID", status, "a cashless account must never be marked PAID");
        assertTrue(TERMINAL_LOTTERY.contains(status), "must still land on a terminal status");
    }

    @Test
    void lotteryWinFrequencyApproachesWinRate() {
        long userId = seedInMarket();
        forceEra(userId, EraCode.CRASH); // winRate 0.35
        setBalance(userId, asDecimal("100000000.00"));

        int n = 400;
        // 订阅只允许在申购日当天发生；为批量抽签采样，直接预埋 SUBSCRIBED 记录
        //（onDayOpen 读的是既有 SUBSCRIBED 行，摇号路径与真实订阅一致）。
        for (int i = 0; i < n; i++) {
            int d = 10 + i;
            UserScriptDay sd = insertIpo(userId, d, "新股" + i, 10.0);
            insertSubscribed(userId, sd.getId(), "新股" + i, "10.00", d);
        }
        for (int i = 0; i < n; i++) {
            int d = 10 + i;
            setCurrentDay(userId, d + 2);
            ipoService.onDayOpen(userId, gameState(userId), board(userId), d + 2);
        }

        List<UserIpoRecord> rows = userIpoRecordMapper.selectList(
                new LambdaQueryWrapper<UserIpoRecord>().eq(UserIpoRecord::getUserId, userId));
        long lost = rows.stream().filter(r -> "LOST".equals(r.getStatus())).count();
        assertEquals(0, rows.stream().filter(r -> "SUBSCRIBED".equals(r.getStatus())).count(),
                "every subscribed record must be settled");
        // 中签（PAID/SETTLED/ABANDONED，= 非 LOST）：胜者先 PAID，随迭代推进的 D+4 上市
        // 又结算为 SETTLED 并入账，故以 LOST 反推赢率最稳。
        double fraction = (double) (rows.size() - lost) / rows.size();
        assertTrue(fraction >= 0.25 && fraction <= 0.45,
                "CRASH win fraction " + fraction + " must be within [0.25, 0.45] of 0.35");
    }

    // ---- 资金守恒 ----

    @Test
    void listingCreditsSellProceedsAndProfitEqualsBalanceDelta() {
        long userId = seedInMarket();
        forceEra(userId, EraCode.BULL_START);
        UserScriptDay sd = insertIpo(userId, 1, "雷霆安防", 10.0);
        long lotSeed = seedWin("ipoLottery" + sd.getId(), 0.30);
        setWorldSeed(userId, lotSeed);
        UserIpoRecord rec = ipoService.subscribe(userId, sd.getId());
        BigDecimal initial = sysUser(userId).getBalance();
        assertEquals(0, asDecimal("100000.00").compareTo(initial), "seed cash must be 100000");

        setCurrentDay(userId, 3);
        ipoService.onDayOpen(userId, gameState(userId), board(userId), 3);
        assertEquals("PAID", userIpoRecordMapper.selectById(rec.getId()).getStatus(),
                "the chosen seed must win so the payment leg is exercised");
        assertEquals(0, asDecimal("90000.00").compareTo(sysUser(userId).getBalance()),
                "payment must deduct issuePrice*1000 from the 100000 cash");

        setCurrentDay(userId, 5);
        ipoService.onDayOpen(userId, gameState(userId), board(userId), 5);
        UserIpoRecord settled = userIpoRecordMapper.selectById(rec.getId());
        assertEquals("SETTLED", settled.getStatus());
        BigDecimal roundTripDelta = sysUser(userId).getBalance().subtract(initial);
        assertEquals(0, settled.getProfit().subtract(roundTripDelta).compareTo(BigDecimal.ZERO),
                "recorded profit must equal the full payment+listing balance delta (-cost + proceeds)");
    }

    @Test
    void bearMarketWithZeroBoardsBreaksIssueAtNinetyPercent() {
        long userId = seedInMarket();
        forceEra(userId, EraCode.CRASH);
        UserScriptDay sd = insertIpo(userId, 1, "雷霆安防", 10.0);
        long listSeed = seedInt("ipoList" + sd.getId(), 3, 0); // CRASH rollBoards(nextInt(3)) == 0
        setWorldSeed(userId, listSeed);
        userIpoRecordMapper.insert(paidRecord(userId, sd.getId(), "雷霆安防", "10.00"));

        setCurrentDay(userId, 5);
        ipoService.onDayOpen(userId, gameState(userId), board(userId), 5);

        UserIpoRecord settled = userIpoRecordMapper.selectOne(new LambdaQueryWrapper<UserIpoRecord>()
                .eq(UserIpoRecord::getUserId, userId));
        assertEquals("SETTLED", settled.getStatus());
        assertEquals(0, asDecimal("-1000.00").compareTo(settled.getProfit()),
                "破发 (10.0 x 0.9) must book a 1000 loss, got " + settled.getProfit());
        assertTrue(settled.getProfit().signum() < 0, "破发 profit must be negative");
    }

    // ---- 打新零行情 ----

    @Test
    void fullIpoRoundTripDoesNotGrowStockState() {
        long userId = seedInMarket();
        forceEra(userId, EraCode.BULL_START);
        UserScriptDay sd = insertIpo(userId, 1, "雷霆安防", 10.0);
        setWorldSeed(userId, seedWin("ipoLottery" + sd.getId(), 0.30));
        UserIpoRecord rec = ipoService.subscribe(userId, sd.getId());

        setCurrentDay(userId, 3);
        ipoService.onDayOpen(userId, gameState(userId), board(userId), 3);
        assertEquals("PAID", userIpoRecordMapper.selectById(rec.getId()).getStatus());

        setCurrentDay(userId, 5);
        ipoService.onDayOpen(userId, gameState(userId), board(userId), 5);
        assertEquals("SETTLED", userIpoRecordMapper.selectById(rec.getId()).getStatus());

        Long rows = userStockStateMapper.selectCount(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId));
        assertEquals((long) StockDefs.ALL_INSTRUMENTS.size(), rows,
                "IPO settlement must stay off the 24-stock + ETF board (no new stock_state)");
    }

    // ---- 辅助 ----

    private void assertBoardsIn(EraCode era, Random rng, int lo, int hi, int samples, String msg) {
        for (int i = 0; i < samples; i++) {
            int n = IpoService.rollBoards(era, rng);
            assertTrue(n >= lo && n <= hi, msg + " got " + n);
        }
    }

    private long seedInMarket() {
        long userId = worldSeeder.seedUser("ipo_" + System.nanoTime(), "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(DEFAULT_SEED);
        userGameStateMapper.updateById(gs);
        // 抹掉播种器自带的 IPO 行，避免与测试手工插入的 (userId, gameDay, IPO) 撞唯一键，
        // 也让 onDayOpen 只按测试注入的申购日分派。
        userScriptDayMapper.delete(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId)
                .eq(UserScriptDay::getScriptType, "IPO"));
        marketStateService.startDay(userId);
        return userId;
    }

    private UserScriptDay insertIpo(long userId, int gameDay, String ipoName, double issuePrice) {
        return insertScriptDay(userId, gameDay, "IPO", Map.of("ipoName", ipoName, "issuePrice", issuePrice));
    }

    private UserScriptDay insertScriptDay(long userId, int gameDay, String scriptType, Map<String, Object> params) {
        UserScriptDay sd = new UserScriptDay();
        sd.setUserId(userId);
        sd.setGameDay(gameDay);
        sd.setScriptType(scriptType);
        sd.setParamsJson(MAPPER.writeValueAsString(params));
        userScriptDayMapper.insert(sd);
        return sd;
    }

    private void insertSubscribed(long userId, long scriptDayId, String ipoName, String issuePrice, int gameDay) {
        UserIpoRecord rec = new UserIpoRecord();
        rec.setUserId(userId);
        rec.setScriptDayId(scriptDayId);
        rec.setIpoName(ipoName);
        rec.setIssuePrice(new BigDecimal(issuePrice));
        rec.setStatus("SUBSCRIBED");
        rec.setGameDay(gameDay);
        userIpoRecordMapper.insert(rec);
    }

    private UserIpoRecord paidRecord(long userId, long scriptDayId, String ipoName, String issuePrice) {
        UserIpoRecord rec = new UserIpoRecord();
        rec.setUserId(userId);
        rec.setScriptDayId(scriptDayId);
        rec.setIpoName(ipoName);
        rec.setIssuePrice(new BigDecimal(issuePrice));
        rec.setStatus("PAID");
        rec.setGameDay(1);
        return rec;
    }

    private void forceEra(long userId, EraCode code) {
        board(userId).setCurrentEra(new EraInstance(1, code, 1, GameMode.STANDARD.totalDays(), 0.0, Map.of()));
    }

    private QuoteBoard board(long userId) {
        return quoteBoardRegistry.getOrLoad(userId);
    }

    private void setCurrentDay(long userId, int day) {
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(day);
        userGameStateMapper.updateById(gs);
    }

    private void setWorldSeed(long userId, long seed) {
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(seed);
        userGameStateMapper.updateById(gs);
    }

    private void setBalance(long userId, BigDecimal balance) {
        SysUser u = sysUser(userId);
        u.setBalance(balance);
        sysUserMapper.updateById(u);
    }

    private List<UserIpoRecord> ipoRecordMapper(long userId, long scriptDayId) {
        return userIpoRecordMapper.selectList(new LambdaQueryWrapper<UserIpoRecord>()
                .eq(UserIpoRecord::getUserId, userId)
                .eq(UserIpoRecord::getScriptDayId, scriptDayId));
    }

    private UserMessage notificationContaining(long userId, String text) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                        .eq(UserMessage::getUserId, userId)
                        .eq(UserMessage::getType, "NOTIFY"))
                .stream().filter(m -> m.getContent() != null && m.getContent().contains(text))
                .findFirst().orElse(null);
    }

    private SysUser sysUser(long userId) {
        return sysUserMapper.selectById(userId);
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    /** 找让给定盐的抽签必中的 worldSeed：WorldRandom.nextDouble() < rate。 */
    private static long seedWin(String salt, double rate) {
        for (long s = 1; ; s++) {
            if (WorldRandom.of(s, salt).nextDouble() < rate) {
                return s;
            }
        }
    }

    /** 找让给定盐的 rollBoards(nextInt(bound)) 命中 target 的 worldSeed。 */
    private static long seedInt(String salt, int bound, int target) {
        for (long s = 1; ; s++) {
            if (WorldRandom.of(s, salt).nextInt(bound) == target) {
                return s;
            }
        }
    }

    private static BigDecimal asDecimal(String value) {
        return new BigDecimal(value);
    }
}
