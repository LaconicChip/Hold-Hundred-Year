package com.holdhunderdyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.game.constants.Titles;
import com.holdhunderdyear.market.entity.TradeRecord;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserIpoRecord;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserPosition;
import com.holdhunderdyear.market.entity.UserScriptDay;
import com.holdhunderdyear.market.entity.UserTitle;
import com.holdhunderdyear.market.mapper.TradeRecordMapper;
import com.holdhunderdyear.market.mapper.UserIpoRecordMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserPositionMapper;
import com.holdhunderdyear.market.mapper.UserScriptDayMapper;
import com.holdhunderdyear.market.mapper.UserTitleMapper;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.game.constants.Titles.Title;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家成长进度服务（Task 10.1，spec §7.7 段位与徽章）：由
 * {@link com.holdhunderdyear.market.SettlementService#settle} 在非破产路径、段位评定之后调用，
 * 处理前 3 天的新手引导气泡与五项成就徽章/称号。
 *
 * <p>徽章/称号达成即写 user_title 行 + 一条祝贺消息（source=SYSTEM、type=NOTIFY），绝不改现金余额。
 * 以 user_title 落库行作为"已解锁"权威判定（配合唯一索引 uk_user_title 兜底幂等），故同条件持续满足
 * 多次结算只解锁一次。新手引导按 (userId, gameDay) 进程内去重，每日一弹。
 *
 * <p>由 {@link #clearUserState} 重开清理。
 */
@Service
public class ProgressService {

    /** 新手引导三步文案（下标 = 步数 - 1，仅前 3 天触发）。 */
    private static final String[] ONBOARDING_STEPS = {
            "点击买入第一手试试水",
            "消息中心有传闻，注意辨别真假",
            "试试快进跳过当日"
    };

    /** 打新中签认可的记录状态（缴款后 PAID 或上市结算 SETTLED）。 */
    private static final Set<String> IPO_HIT_STATUS = Set.of("PAID", "SETTLED");

    /** 新手引导每日去重：userId → 已弹引导的 gameDay 集合。 */
    private final Map<Long, Set<Integer>> onboardingDays = new ConcurrentHashMap<>();

    private final UserTitleMapper userTitleMapper;
    private final UserMessageMapper userMessageMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final UserScriptDayMapper userScriptDayMapper;
    private final UserPositionMapper userPositionMapper;
    private final UserIpoRecordMapper userIpoRecordMapper;

    public ProgressService(UserTitleMapper userTitleMapper,
                           UserMessageMapper userMessageMapper,
                           TradeRecordMapper tradeRecordMapper,
                           UserScriptDayMapper userScriptDayMapper,
                           UserPositionMapper userPositionMapper,
                           UserIpoRecordMapper userIpoRecordMapper) {
        this.userTitleMapper = userTitleMapper;
        this.userMessageMapper = userMessageMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.userScriptDayMapper = userScriptDayMapper;
        this.userPositionMapper = userPositionMapper;
        this.userIpoRecordMapper = userIpoRecordMapper;
    }

    /**
     * 收盘结算的非破产路径调用：先推前 3 天新手引导气泡，再逐一评估五项徽章/称号。
     * <p>原则：徽章解锁绝不动现金，只写称号与祝贺消息；已落库的成就以唯一索引幂等跳过。
     */
    public void onSettle(long userId, int day, UserGameState gs, QuoteBoard board) {
        maybeOnboarding(userId, day);
        maybeFirstTrade(userId, day);
        maybeDodgeLimitDownAll(userId, day);
        maybeFiveStreak(userId, day, board);
        maybeIpoLottery(userId, day);
        maybeIdleTenDays(userId, day);
    }

    /** 重开/登出时清除该 userId 的引导去重记录。 */
    public void clearUserState(long userId) {
        onboardingDays.remove(userId);
    }

    // ---- 新手引导 ----

    /** 前 3 天各推一次引导气泡，同一 (userId, gameDay) 只弹一次。 */
    private void maybeOnboarding(long userId, int day) {
        if (day < 1 || day > ONBOARDING_STEPS.length) {
            return;
        }
        if (!onboardingDays.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(day)) {
            return;
        }
        notify(userId, day, "新手引导", ONBOARDING_STEPS[day - 1]);
    }

    // ---- 徽章/称号 ----

    /** 初入股市（TITLE）：存在任意一笔成交即可解锁。 */
    private void maybeFirstTrade(long userId, int day) {
        boolean anyTrade = tradeRecordMapper.selectCount(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId)) > 0;
        unlock(userId, day, codeOf("first-trade"), anyTrade,
                "您完成了人生第一笔交易，正式踏入 A 股江湖，初入股市。");
    }

    /** 空仓躲过千股跌停（BADGE）：当日为千股跌停剧本日且玩家空仓。 */
    private void maybeDodgeLimitDownAll(long userId, int day) {
        boolean limitDownAll = userScriptDayMapper.selectCount(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId)
                .eq(UserScriptDay::getGameDay, day)
                .eq(UserScriptDay::getScriptType, "LIMIT_DOWN_ALL")) > 0;
        boolean flat = userPositionMapper.selectCount(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId)
                .gt(UserPosition::getShares, 0)) == 0;
        unlock(userId, day, codeOf("dodge-limit-down-all"), limitDownAll && flat,
                "千股跌停之夜您空仓避险，完美躲过一场浩劫。");
    }

    /** 抓住 5 连板（BADGE）：行情板内任一股票连板数 ≥ 5。 */
    private void maybeFiveStreak(long userId, int day, QuoteBoard board) {
        boolean fiveStreak = board.getQuotes().values().stream().anyMatch(q -> q.getLimitUpStreak() >= 5);
        unlock(userId, day, codeOf("five-streak"), fiveStreak,
                "慧眼如炬，抓住 5 连板妖股的一天。");
    }

    /** 打新中签（BADGE）：存在一条中签记录（PAID 或 SETTLED）。 */
    private void maybeIpoLottery(long userId, int day) {
        long hit = userIpoRecordMapper.selectCount(new LambdaQueryWrapper<UserIpoRecord>()
                .eq(UserIpoRecord::getUserId, userId)
                .in(UserIpoRecord::getStatus, IPO_HIT_STATUS));
        unlock(userId, day, codeOf("ipo-lottery"), hit > 0,
                "您打新中签，稳稳握住了这份好运。");
    }

    /** 连续 10 天不操作（BADGE）：今日起回看 [day-9, day] 窗口内无任何成交。 */
    private void maybeIdleTenDays(long userId, int day) {
        if (day < 10) {
            return;
        }
        boolean tradedInWindow = tradeRecordMapper.selectCount(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId)
                .ge(TradeRecord::getGameDay, day - 9)) > 0;
        unlock(userId, day, codeOf("idle-ten-days"), !tradedInWindow,
                "连续 10 个交易日按兵不动，耐心也是一种美德。");
    }

    // ---- 通用落库 ----

    /** 条件满足且未解锁时：写 user_title 行 + 祝贺消息；已解锁则静默（唯一索引兜底幂等）。 */
    private void unlock(long userId, int day, Titles.Title title, boolean condition, String content) {
        if (!condition) {
            return;
        }
        if (userTitleMapper.selectCount(new LambdaQueryWrapper<UserTitle>()
                .eq(UserTitle::getUserId, userId)
                .eq(UserTitle::getTitleCode, title.code())) > 0) {
            return;
        }
        UserTitle earned = new UserTitle();
        earned.setUserId(userId);
        earned.setTitleCode(title.code());
        earned.setTitleName(title.name());
        earned.setKind(title.kind());
        earned.setGameDay(day);
        userTitleMapper.insert(earned);
        notify(userId, day, title.name(), content);
    }

    /** 系统祝贺/引导气泡落库（source=SYSTEM、type=NOTIFY、未读、当日）。 */
    private void notify(long userId, int day, String title, String content) {
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource("SYSTEM");
        message.setType("NOTIFY");
        message.setTitle(title);
        message.setContent(content);
        message.setIsRead(false);
        message.setGameDay(day);
        userMessageMapper.insert(message);
    }

    /** 按 code 从 Titles.ALL 取 Title；未知 code 属编程错误直接抛。 */
    private static Title codeOf(String code) {
        for (Title title : Titles.ALL) {
            if (title.code().equals(code)) {
                return title;
            }
        }
        throw new IllegalArgumentException("Unknown title code: " + code);
    }
}