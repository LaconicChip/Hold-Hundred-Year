package com.holdhundredyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserIpoRecord;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.entity.UserTitle;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserIpoRecordMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.market.mapper.UserTitleMapper;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 段位/徽章/新手引导（Task 10.1）集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖 ProgressService.onSettle：前 3 天新手引导气泡（每日一条、去重）、五项徽章/称号
 * （初入股市/空仓躲千股跌停/抓 5 连板/打新中签/连续 10 天不操作）的解锁条件与唯一索引幂等、
 * 解锁不改现金余额、clearUserState 重置引导去重。
 */
@SpringBootTest
@Transactional
class ProgressServiceTest {

    @Autowired
    private ProgressService progressService;

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private QuoteBoardRegistry quoteBoardRegistry;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserTitleMapper userTitleMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Autowired
    private TradeRecordMapper tradeRecordMapper;

    @Autowired
    private UserScriptDayMapper userScriptDayMapper;

    @Autowired
    private UserPositionMapper userPositionMapper;

    @Autowired
    private UserIpoRecordMapper userIpoRecordMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    // ---- 新手引导 ----

    @Test
    void onboardingEmitOncePerDayForFirstThreeDays() {
        long userId = seed();

        onSettle(userId, 1);
        onSettle(userId, 1); // 同日不得重复
        assertEquals(1, onboardingCount(userId, 1), "day 1 onboarding must fire exactly once");
        assertEquals("点击买入第一手试试水", onboardingText(userId).get(0).getContent());

        onSettle(userId, 2);
        assertEquals(1, onboardingCount(userId, 2), "day 2 onboarding must fire exactly once");
        assertEquals("消息中心有传闻，注意辨别真假", onboardingText(userId, 2).get(0).getContent());

        onSettle(userId, 3);
        assertEquals(1, onboardingCount(userId, 3), "day 3 onboarding must fire exactly once");
        assertEquals("试试快进跳过当日", onboardingText(userId, 3).get(0).getContent());
    }

    @Test
    void onboardingDoesNotFireAfterThirdDay() {
        long userId = seed();
        onSettle(userId, 4);
        assertTrue(messages(userId).stream().noneMatch(m -> "新手引导".equals(m.getTitle())),
                "onboarding must not fire after day 3");
    }

    // ---- 初入股市 ----

    @Test
    void firstTradeUnlocksTitleOnce() {
        long userId = seed();
        insertTrade(userId, 1L, "BUY", 2);

        onSettle(userId, 3);

        assertEquals(1, titles(userId, "first-trade").size(), "first trade must unlock the TITLE");
        UserTitle title = titles(userId, "first-trade").get(0);
        assertEquals("TITLE", title.getKind());
        assertEquals("初入股市", title.getTitleName());

        onSettle(userId, 4);
        assertEquals(1, titles(userId, "first-trade").size(), "the TITLE must not be earned twice");
    }

    @Test
    void firstTradeTitleNeedsAtLeastOneTrade() {
        long userId = seed();
        onSettle(userId, 3);
        assertTrue(titles(userId, "first-trade").isEmpty(), "no trade must not unlock the TITLE");
    }

    // ---- 空仓躲千股跌停 ----

    @Test
    void emptyPositionOnLimitDownAllDayUnlocksBadge() {
        long userId = seed();
        setLimitDownAll(userId, 15);
        // 播种默认无持仓，天然满足空仓

        onSettle(userId, 15);

        assertEquals(1, titles(userId, "dodge-limit-down-all").size(),
                "flat on a 千股跌停 day must unlock the badge");
        assertEquals("BADGE", titles(userId, "dodge-limit-down-all").get(0).getKind());
    }

    @Test
    void holdingPositionOnLimitDownAllDayDoesNotUnlockBadge() {
        long userId = seed();
        setLimitDownAll(userId, 16);
        insertPosition(userId, 1L, 200, 200, "150.00");

        onSettle(userId, 16);

        assertTrue(titles(userId, "dodge-limit-down-all").isEmpty(),
                "holding a position on a 千股跌停 day must not unlock the badge");
    }

    // ---- 抓住 5 连板 ----

    @Test
    void fiveLimitUpStreakUnlocksBadge() {
        long userId = seed();
        quoteBoardRegistry.getOrLoad(userId).quote(1L).setLimitUpStreak(5);

        onSettle(userId, 7);

        assertEquals(1, titles(userId, "five-streak").size(),
                "a 5-position limit-up streak must unlock the badge");
    }

    @Test
    void belowFiveLimitUpStreakDoesNotUnlockBadge() {
        long userId = seed();
        quoteBoardRegistry.getOrLoad(userId).quote(1L).setLimitUpStreak(2);

        onSettle(userId, 7);

        assertTrue(titles(userId, "five-streak").isEmpty(), "a 2-position streak must not unlock the badge");
    }

    // ---- 打新中签 ----

    @Test
    void paidIpoUnlocksBadge() {
        long userId = seed();
        insertIpo(userId, "PAID");

        onSettle(userId, 6);

        assertEquals(1, titles(userId, "ipo-lottery").size(),
                "an IPO that was drawn (PAID) must unlock the badge");
    }

    @Test
    void settledIpoUnlocksBadge() {
        long userId = seed();
        insertIpo(userId, "SETTLED");

        onSettle(userId, 6);

        assertEquals(1, titles(userId, "ipo-lottery").size(),
                "an IPO that was settled must unlock the badge");
    }

    @Test
    void subscribedOnlyIpoDoesNotUnlockBadge() {
        long userId = seed();
        insertIpo(userId, "SUBSCRIBED");

        onSettle(userId, 6);

        assertTrue(titles(userId, "ipo-lottery").isEmpty(), "a mere SUBSCRIBED record must not unlock the badge");
    }

    // ---- 连续 10 天不操作 ----

    @Test
    void noTradeForTenConsecutiveDaysUnlocksBadge() {
        long userId = seed();
        // 播种默认无成交

        onSettle(userId, 12);

        assertEquals(1, titles(userId, "idle-ten-days").size(),
                "ten consecutive days without a trade at the window [3,12] must unlock the badge");
    }

    @Test
    void tradeInsideWindowPreventsIdleBadge() {
        long userId = seed();
        insertTrade(userId, 1L, "SELL", 5);

        onSettle(userId, 12);

        assertTrue(titles(userId, "idle-ten-days").isEmpty(),
                "a trade inside the previous 10-day window must not unlock the badge");
    }

    @Test
    void idleBadgeNeedsAtLeastTenthDay() {
        long userId = seed();
        onSettle(userId, 9);
        assertTrue(titles(userId, "idle-ten-days").isEmpty(), "day 9 is not yet ten consecutive days idle");
    }

    // ---- 不给现金 ----

    @Test
    void unlockingBadgesNeverChangesBalance() {
        long userId = seed();
        setLimitDownAll(userId, 20);
        insertTrade(userId, 1L, "BUY", 1); // 触发初入股市
        insertIpo(userId, "PAID");          // 触发打新中签
        quoteBoardRegistry.getOrLoad(userId).quote(1L).setLimitUpStreak(5); // 触发抓 5 连板
        SysUser user = sysUserMapper.selectById(userId);
        BigDecimal before = user.getBalance();

        onSettle(userId, 20);

        assertEquals(before, sysUserMapper.selectById(userId).getBalance(),
                "badge/title unlocks must never touch the cash balance");
    }

    // ---- 幂等 / 去重 ----

    @Test
    void repeatedSettleUnlocksEachBadgeOnlyOnce() {
        long userId = seed();
        insertTrade(userId, 1L, "BUY", 1);

        onSettle(userId, 3);
        onSettle(userId, 4);
        onSettle(userId, 5);

        assertEquals(1, titles(userId, "first-trade").size(),
                "the unique index must keep only one row per (user, code)");
    }

    // ---- clearUserState ----

    @Test
    void clearUserStateResetsOnboardingDedup() {
        long userId = seed();
        onSettle(userId, 1);
        assertEquals(1, onboardingCount(userId, 1));

        progressService.clearUserState(userId);
        onSettle(userId, 1);
        assertEquals(2, onboardingCount(userId, 1),
                "clearUserState must allow the day-1 onboarding bubble to fire again");
    }

    // ---- 造数 helper ----

    private long seed() {
        long userId = worldSeeder.seedUser("titles_" + System.nanoTime(), "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(20260904L);
        userGameStateMapper.updateById(gs);
        return userId;
    }

    private void onSettle(long userId, int day) {
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(day);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        progressService.onSettle(userId, day, gs, board);
    }

    /** 构造一个千股跌停剧本日：清掉既有剧本日避免 unique(user,day,type) 冲突后插入。 */
    private void setLimitDownAll(long userId, int day) {
        userScriptDayMapper.delete(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId));
        UserScriptDay scriptDay = new UserScriptDay();
        scriptDay.setUserId(userId);
        scriptDay.setGameDay(day);
        scriptDay.setScriptType("LIMIT_DOWN_ALL");
        userScriptDayMapper.insert(scriptDay);
    }

    private void insertTrade(long userId, long stockId, String type, int gameDay) {
        TradeRecord record = new TradeRecord();
        record.setUserId(userId);
        record.setStockId(stockId);
        record.setTradeType(type);
        record.setPrice(BigDecimal.ONE);
        record.setShares(100);
        record.setAmount(BigDecimal.ONE);
        record.setCommission(BigDecimal.ONE);
        record.setTax(BigDecimal.ZERO);
        record.setGameDay(gameDay);
        tradeRecordMapper.insert(record);
    }

    private void insertIpo(long userId, String status) {
        UserIpoRecord record = new UserIpoRecord();
        record.setUserId(userId);
        record.setScriptDayId(1L);
        record.setIpoName("测试新股");
        record.setIssuePrice(BigDecimal.ONE);
        record.setStatus(status);
        record.setGameDay(5);
        userIpoRecordMapper.insert(record);
    }

    private void insertPosition(long userId, long stockId, int shares, int available, String avgCost) {
        UserPosition position = new UserPosition();
        position.setUserId(userId);
        position.setStockId(stockId);
        position.setShares(shares);
        position.setAvailableShares(available);
        position.setAvgCost(new BigDecimal(avgCost));
        userPositionMapper.insert(position);
    }

    private List<UserTitle> titles(long userId, String code) {
        return userTitleMapper.selectList(new LambdaQueryWrapper<UserTitle>()
                .eq(UserTitle::getUserId, userId)
                .eq(UserTitle::getTitleCode, code));
    }

    private List<UserMessage> onboardingText(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getTitle, "新手引导")
                .orderByAsc(UserMessage::getGameDay));
    }

    private List<UserMessage> onboardingText(long userId, int day) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getTitle, "新手引导")
                .eq(UserMessage::getGameDay, day));
    }

    private long onboardingCount(long userId, int day) {
        return userMessageMapper.selectCount(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getTitle, "新手引导")
                .eq(UserMessage::getGameDay, day));
    }

    private List<UserMessage> messages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }
}