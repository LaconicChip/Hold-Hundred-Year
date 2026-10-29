package com.holdhundredyear.trade;

import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.event.NotifyPublisher;
import com.holdhundredyear.game.model.enums.EraCode;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserIpoRecord;
import com.holdhundredyear.market.entity.UserScriptDay;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserIpoRecordMapper;
import com.holdhundredyear.market.mapper.UserScriptDayMapper;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldRandom;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 打新服务（spec §6.5 / 交接 §5 Task 9.2）：IPO 申购、T+2 抽签缴款、T+4 上市开板结算。
 * 每个 IPO script_day（申购日 D）独立走三阶段时序，由 {@link #onDayOpen} 在开盘管线统一分派：
 * <ul>
 *   <li>D（申购日）：只推送"今日申购"通知，不自动建记录（需玩家顶格申购）；</li>
 *   <li>D+2（中签缴款日）：对 SUBSCRIBED 记录逐个摇号，中签且资金足 → PAID 扣款，
 *       资金不足 → ABANDONED，未中签 → LOST；</li>
 *   <li>D+4（上市日）：对 PAID 记录掷连板数 N，开板价 = issuePrice×1.1^N（熊市破发 → ×0.9），
 *       自动卖出入账、profit 与 status=SETTLED 落库并推送卖出入账消息。</li>
 * </ul>
 * 打新零行情：全程不触碰 24 只二级市场股票、不新增 stock_state，纯事件结算。
 *
 * <p>随机性全部经 {@code WorldRandom.of(worldSeed, salt)}，盐固定于该 IPO 的
 * scriptDayId（"ipoLottery"+id 抽签、"ipoList"+id 连板），同 worldSeed 必得同结果。
 * 赢率/连板按当日记元（board.getCurrentEra()）定价：赢率新纪元牛市偏低、熊市偏高，连板数
 * 随纪元强弱分布（见 {@link #winRate} / {@link #rollBoards} / {@link #breaksIssue}）。
 */
@Service
public class IpoService {

    /** 记录状态机。 */
    static final String SUBSCRIBED = "SUBSCRIBED";
    static final String PAID = "PAID";
    static final String LOST = "LOST";
    static final String ABANDONED = "ABANDONED";
    static final String SETTLED = "SETTLED";

    /** 熊市纪元集合（{@link #rollBoards}/{@link #breaksIssue} 共用）。 */
    private static final Set<EraCode> BEAR_ERAS = Set.of(
            EraCode.CRASH, EraCode.TRADE_WAR, EraCode.BLACK_JULY, EraCode.CIRCUIT_BREAKER);

    private static final BigDecimal ONE_POINT_ONE = new BigDecimal("1.1");
    private static final BigDecimal NINE_TENTHS = new BigDecimal("0.9");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final UserScriptDayMapper userScriptDayMapper;
    private final UserIpoRecordMapper userIpoRecordMapper;
    private final UserGameStateMapper userGameStateMapper;
    private final SysUserMapper sysUserMapper;
    private final NotifyPublisher notifyPublisher;
    private final com.holdhundredyear.ws.PushService pushService;

    public IpoService(UserScriptDayMapper userScriptDayMapper,
                      UserIpoRecordMapper userIpoRecordMapper,
                      UserGameStateMapper userGameStateMapper,
                      SysUserMapper sysUserMapper,
                      NotifyPublisher notifyPublisher,
                      com.holdhundredyear.ws.PushService pushService) {
        this.userScriptDayMapper = userScriptDayMapper;
        this.userIpoRecordMapper = userIpoRecordMapper;
        this.userGameStateMapper = userGameStateMapper;
        this.sysUserMapper = sysUserMapper;
        this.notifyPublisher = notifyPublisher;
        this.pushService = pushService;
    }

    /** 开盘整合点（由 MarketStateService.openDay 在 setCurrentEra 之后、状态落库前调用）：
     *  按时序分派该用户所有 IPO script_day 行的申购通知 / 中签缴款 / 上市结算。 */
    public void onDayOpen(long userId, UserGameState gs, QuoteBoard board, int newDay) {
        List<UserScriptDay> ipos = userScriptDayMapper.selectList(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId)
                .eq(UserScriptDay::getScriptType, "IPO"));
        for (UserScriptDay sd : ipos) {
            int d = sd.getGameDay();
            if (newDay == d) {
                notifySubscribe(userId, sd, newDay);
            } else if (newDay == d + 2) {
                runLottery(userId, gs, board, sd, newDay);
            } else if (newDay == d + 4) {
                settleListings(userId, gs, board, sd, newDay);
            }
        }
    }

    /**
     * 顶格申购（幂等）：scriptDayId 必须属于本用户、scriptType=IPO、且 gameDay==申购日 D==当前交易日；
     * 已有该 (userId, scriptDayId) 记录时原样返回（不重复插入）；否则建一则 status=SUBSCRIBED 的记录并返回。
     */
    public UserIpoRecord subscribe(long userId, long scriptDayId) {
        UserScriptDay sd = userScriptDayMapper.selectById(scriptDayId);
        if (sd == null || !sd.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        if (!"IPO".equals(sd.getScriptType())) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        UserGameState gs = gameState(userId);
        if (sd.getGameDay() != gs.getCurrentDay()) {
            // 过期申购不是"请求格式错"：前端把昨天的通知又弹出来了，要告诉玩家为什么点不动
            throw new BizException(ErrorCode.IPO_EXPIRED);
        }
        UserIpoRecord existing = userIpoRecordMapper.selectOne(new LambdaQueryWrapper<UserIpoRecord>()
                .eq(UserIpoRecord::getUserId, userId)
                .eq(UserIpoRecord::getScriptDayId, scriptDayId));
        if (existing != null) {
            return existing;
        }
        JsonNode params = MAPPER.readTree(sd.getParamsJson());
        UserIpoRecord rec = new UserIpoRecord();
        rec.setUserId(userId);
        rec.setScriptDayId(scriptDayId);
        rec.setIpoName(params.get("ipoName").asString());
        rec.setIssuePrice(BigDecimal.valueOf(params.get("issuePrice").asDouble()));
        rec.setStatus(SUBSCRIBED);
        rec.setGameDay(sd.getGameDay());
        rec.setCreatedAt(LocalDateTime.now());
        userIpoRecordMapper.insert(rec);
        return rec;
    }

    /** 赢率（spec §6.5）：牛市偏低、熊市偏高——疯牛 0.22、结构牛 0.28、熊市 0.35、其余 0.30。 */
    static double winRate(EraCode era) {
        EraCode e = (era == null) ? EraCode.BULL_START : era;
        switch (e) {
            case EUPHORIA:
                return 0.22;
            case NEW_ENERGY:
            case AI_BULL:
                return 0.28;
            case CRASH:
            case TRADE_WAR:
            case BLACK_JULY:
            case CIRCUIT_BREAKER:
                return 0.35;
            default:
                return 0.30;
        }
    }

    /** 连板数（spec §6.5）：疯牛 6~12、结构牛 3~6、熊市 0~2、其余 0~4。 */
    static int rollBoards(EraCode era, Random rng) {
        EraCode e = (era == null) ? EraCode.BULL_START : era;
        switch (e) {
            case EUPHORIA:
                return 6 + rng.nextInt(7);
            case NEW_ENERGY:
            case AI_BULL:
                return 3 + rng.nextInt(4);
            case CRASH:
            case TRADE_WAR:
            case BLACK_JULY:
            case CIRCUIT_BREAKER:
                return rng.nextInt(3);
            default:
                return rng.nextInt(5);
        }
    }

    /** 破发判定：仅熊市且连板数 0 时开板价下探至发行价 ×0.9。 */
    static boolean breaksIssue(EraCode era, int boards) {
        EraCode e = (era == null) ? EraCode.BULL_START : era;
        return BEAR_ERAS.contains(e) && boards == 0;
    }

    /** 申购日通知："XX 新股今日申购，发行价 Y 元"（SYSTEM/NOTIFY），并推送结构化
     *  打新通知到 notifications 通道（前端 IpoDialog 据 scriptDayId/ipoName/issuePrice 弹申购窗）。 */
    private void notifySubscribe(long userId, UserScriptDay sd, int newDay) {
        JsonNode params = MAPPER.readTree(sd.getParamsJson());
        String name = params.get("ipoName").asString();
        double price = params.get("issuePrice").asDouble();
        insertMessage(userId, "新股申购",
                name + " 新股今日申购，发行价 " + String.format("%.2f", price) + " 元", newDay);
        pushService.pushNotification(userId, Map.of(
                "kind", "IPO_SUBSCRIBE",
                "scriptDayId", sd.getId(),
                "ipoName", name,
                "issuePrice", price,
                "gameDay", newDay));
    }

    /** 中签缴款日 D+2：对每个 SUBSCRIBED 记录逐个摇号。 */
    private void runLottery(long userId, UserGameState gs, QuoteBoard board, UserScriptDay sd, int newDay) {
        List<UserIpoRecord> records = userIpoRecordMapper.selectList(new LambdaQueryWrapper<UserIpoRecord>()
                .eq(UserIpoRecord::getUserId, userId)
                .eq(UserIpoRecord::getScriptDayId, sd.getId())
                .eq(UserIpoRecord::getStatus, SUBSCRIBED));
        if (records.isEmpty()) {
            return;
        }
        Random rng = WorldRandom.of(gs.getWorldSeed(), "ipoLottery" + sd.getId());
        EraCode era = eraOf(board);
        for (UserIpoRecord rec : records) {
            boolean win = rng.nextDouble() < winRate(era);
            if (!win) {
                rec.setStatus(LOST);
                userIpoRecordMapper.updateById(rec);
                insertMessage(userId, "中签结果",
                        "感谢参与，" + rec.getIpoName() + " 新股申购未能中签，敬请期待下一支。", newDay);
                continue;
            }
            BigDecimal cost = rec.getIssuePrice().multiply(THOUSAND).setScale(2, RoundingMode.HALF_UP);
            SysUser user = sysUserMapper.selectById(userId);
            if (user.getBalance().compareTo(cost) >= 0) {
                user.setBalance(user.getBalance().subtract(cost));
                sysUserMapper.updateById(user);
                rec.setStatus(PAID);
                userIpoRecordMapper.updateById(rec);
                insertMessage(userId, "中签缴款",
                        "恭喜中签" + rec.getIpoName() + "，已按发行价扣款 1000 股。", newDay);
            } else {
                rec.setStatus(ABANDONED);
                userIpoRecordMapper.updateById(rec);
                insertMessage(userId, "中签缴款",
                        rec.getIpoName() + " 中签但资金不足，已放弃缴款。", newDay);
            }
        }
    }

    /** 上市结算日 D+4：对每个 status=PAID 记录掷连板、开板卖出入账并落 profit。 */
    private void settleListings(long userId, UserGameState gs, QuoteBoard board, UserScriptDay sd, int newDay) {
        List<UserIpoRecord> records = userIpoRecordMapper.selectList(new LambdaQueryWrapper<UserIpoRecord>()
                .eq(UserIpoRecord::getUserId, userId)
                .eq(UserIpoRecord::getScriptDayId, sd.getId())
                .eq(UserIpoRecord::getStatus, PAID));
        if (records.isEmpty()) {
            return;
        }
        Random rng = WorldRandom.of(gs.getWorldSeed(), "ipoList" + sd.getId());
        EraCode era = eraOf(board);
        for (UserIpoRecord rec : records) {
            int boards = rollBoards(era, rng);
            boolean isBreak = breaksIssue(era, boards);
            BigDecimal issue = rec.getIssuePrice();
            BigDecimal sellPrice = sellPrice(issue, boards, isBreak);
            BigDecimal proceeds = sellPrice.multiply(THOUSAND).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cost = issue.multiply(THOUSAND).setScale(2, RoundingMode.HALF_UP);

            SysUser user = sysUserMapper.selectById(userId);
            user.setBalance(user.getBalance().add(proceeds));
            sysUserMapper.updateById(user);

            BigDecimal profit = proceeds.subtract(cost);
            rec.setProfit(profit);
            rec.setStatus(SETTLED);
            userIpoRecordMapper.updateById(rec);

            String kind = isBreak ? "破发" : ("涨停 " + boards + " 连板");
            insertMessage(userId, "打新卖出", rec.getIpoName() + " 新股开板卖出（" + kind
                    + "），中签盈利 " + profit.toPlainString() + " 元。", newDay);
            pushService.pushNotification(userId, Map.of(
                    "kind", "IPO_SETTLED",
                    "ipoName", rec.getIpoName(),
                    "profit", profit.doubleValue(),
                    "gameDay", newDay));
        }
    }

    /** 开板价：破发 → 发行价 ×0.9；否则 发行价 ×1.1^boards（按分 HALF_UP）。 */
    private BigDecimal sellPrice(BigDecimal issue, int boards, boolean isBreak) {
        if (isBreak) {
            return issue.multiply(NINE_TENTHS).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal pow = ONE_POINT_ONE.pow(boards);
        return issue.multiply(pow).setScale(2, RoundingMode.HALF_UP);
    }

    /** 当日记元：开盘管线已 setCurrentEra；防御兜底（按期应非空）回退牛市启动。 */
    private EraCode eraOf(QuoteBoard board) {
        return board.getCurrentEra() != null ? board.getCurrentEra().code() : EraCode.BULL_START;
    }

    private UserGameState gameState(long userId) {
        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        if (gs == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gs;
    }

    /** 打新提醒落库（参数顺序沿用本服务习惯，字段惯例统一在 {@link NotifyPublisher}）。 */
    private void insertMessage(long userId, String title, String content, int gameDay) {
        notifyPublisher.system(userId, gameDay, title, content);
    }
}
