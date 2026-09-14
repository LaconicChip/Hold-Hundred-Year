package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.event.NpcService;
import com.holdhunderdyear.event.ProgressService;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.market.entity.LoanRecord;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserPosition;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.LoanRecordMapper;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserPositionMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.trade.MarginService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收盘结算器（spec §3 结算 12 步）：由 WorldTickService 在世界锁+事务内调用，自身不加锁。
 * 落地范围：prev_close 双写、T+1 解冻、连板统计与三连板停牌核查、融资计息、
 * 日收益评价与每日快照、段位评定、新手引导与成就徽章、OHL 重置、状态落位。
 * <p>边界：步骤①当前价快照由 WorldTickService.persistSnapshot 完成；追保/强平/爆仓
 * 委托 MarginService 执行（settleCheck，爆仓时快照留痕后短路）；5 日 4 板与异动公告
 * （Phase 8）、打新/除权（Phase 9）均不做；步骤⑩不掷盘后消息，
 * gap_pct 保持 0——无消息则次日无跳空是正确行为。
 */
@Service
public class SettlementService {

    /** 段位升序枚举（spec §7.7，与门槛/中文名同序）。 */
    private static final List<String> TIER_ORDER = List.of("RETAIL", "MIDDLE", "BIG", "HOTSHOT", "MASTER");
    /** 段位净资产门槛（元）：散户 <15 万、中户 ≥15 万、大户 ≥30 万、游资 ≥60 万、牛散 ≥100 万。 */
    private static final double[] TIER_THRESHOLDS = {0, 150_000, 300_000, 600_000, 1_000_000};
    /** 段位中文名（与 TIER_ORDER 同序）。 */
    private static final List<String> TIER_NAMES = List.of("散户", "中户", "大户", "游资", "牛散");

    private final UserGameStateMapper userGameStateMapper;
    private final UserStockStateMapper userStockStateMapper;
    private final UserPositionMapper userPositionMapper;
    private final SysUserMapper sysUserMapper;
    private final LoanRecordMapper loanRecordMapper;
    private final UserDailySnapshotMapper userDailySnapshotMapper;
    private final UserMessageMapper userMessageMapper;
    private final QuoteBoardRegistry quoteBoardRegistry;
    private final MarginService marginService;
    private final NpcService npcService;
    private final ProgressService progressService;

    public SettlementService(UserGameStateMapper userGameStateMapper,
                             UserStockStateMapper userStockStateMapper,
                             UserPositionMapper userPositionMapper,
                             SysUserMapper sysUserMapper,
                             LoanRecordMapper loanRecordMapper,
                             UserDailySnapshotMapper userDailySnapshotMapper,
                             UserMessageMapper userMessageMapper,
                             QuoteBoardRegistry quoteBoardRegistry,
                             MarginService marginService,
                             NpcService npcService,
                             ProgressService progressService) {
        this.userGameStateMapper = userGameStateMapper;
        this.userStockStateMapper = userStockStateMapper;
        this.userPositionMapper = userPositionMapper;
        this.sysUserMapper = sysUserMapper;
        this.loanRecordMapper = loanRecordMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
        this.userMessageMapper = userMessageMapper;
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.marginService = marginService;
        this.npcService = npcService;
        this.progressService = progressService;
    }

    /**
     * 执行收盘结算（spec §3 步骤②-⑫）。
     * <p>口径说明：
     * <ul>
     * <li>prev_close 双写 DB 与内存板——次日跳空（applyOpenGap）与涨跌停价均基于内存 prevClose；</li>
     * <li>prevTotalAsset 存本次净资产而非总资产：日收益率分子是净资产，分母必须同口径
     *     （字段名 total_asset 沿用表结构，语义以本口径为准）；</li>
     * <li>连板/停牌核查/OHL 重置仅作用于非停牌股，停牌股三值全部冻结；</li>
     * <li>三连板停牌写 halt_until_day = 当日 + 2：spec §7.5 的 "current_day+1" 与 "次日起停牌
     *     1 个游戏交易日" 相互矛盾——复牌条件 haltUntilDay &gt; newDay 下 day+1 开盘即复牌，
     *     实际停 0 天。按 "停 1 个完整交易日" 的功能语义拍板当日 + 2（N+1 全天停牌、
     *     N+2 复牌补跌，呼应 §4.4 连环发酵），§7.5 的 +1 判为笔误。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void settle(long userId) {
        UserGameState gs = loadGameState(userId);
        int day = gs.getCurrentDay();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        // 步骤②④⑪：prev_close 双写、连板统计与三连板停牌核查、OHL 重置（停牌股全部冻结）
        Map<Long, UserStockState> stateByStock = settleStocks(userId, day, board);
        // 步骤③：T+1 解冻
        List<UserPosition> positions = unfreezeT1(userId);
        // 步骤⑤：融资计息（spec §6.4 权威口径：日利率 = 年化 8% / 36）
        SysUser user = sysUserMapper.selectById(userId);
        accrueInterest(userId, user, day);

        // 步骤⑤续：追保 + 强平 + 爆仓（MarginService 在调用方事务内原地改账，强平会改
        // 余额/负债/持仓，其后必须重算口径再评价与快照）
        boolean bankrupt = marginService.settleCheck(userId, gs, user, positions, board);
        BigDecimal cash = user.getBalance();
        BigDecimal debt = user.getDebt() != null ? user.getDebt() : BigDecimal.ZERO;
        BigDecimal marketValue = marketValue(positions, stateByStock);
        BigDecimal netAsset = cash.add(marketValue).subtract(debt);

        // NPC 资产 JSON 与角色线消息（Task 8.4）：指数日收益提为一次，两个分支快照前统一生成
        double indexDayPct = indexDayPct(userId, day, gs);
        String npcJson = npcService.dailyAssetsJson(userId, day, gs, board, indexDayPct / 100.0);

        if (bankrupt) {
            // 爆仓终局（spec §6.4）：快照留痕后短路，跳过日收益评价/段位评定/状态落位
            double dayReturnPct = dayReturnPctOf(gs, netAsset);
            insertSnapshot(userId, day, gs, cash, marketValue, debt, netAsset, dayReturnPct, indexDayPct, npcJson);
            gs.setPrevTotalAsset(netAsset);
            userGameStateMapper.updateById(gs);
            return;
        }
        // 步骤⑥：日收益评价（首日 prevTotalAsset 为空按初始资金 10 万基准）
        double dayReturnPct = notifyDailyReview(userId, day, gs, netAsset, indexDayPct);
        // 步骤⑦：段位评定（升档递烟、降档慰问、持平沉默）
        assessTier(userId, day, gs, netAsset);
        // 步骤⑦续：新手引导与成就徽章（Task 10.1，非破产路径才评）
        progressService.onSettle(userId, day, gs, board);
        // 步骤⑧：每日快照
        insertSnapshot(userId, day, gs, cash, marketValue, debt, netAsset, dayReturnPct, indexDayPct, npcJson);
        // 步骤⑫：状态落位
        closeOutDay(userId, day, gs, netAsset, dayReturnPct);

        gs.setPrevTotalAsset(netAsset);
        userGameStateMapper.updateById(gs);
    }

    /** 读取用户世界状态，缺失抛 NOT_FOUND。 */
    private UserGameState loadGameState(long userId) {
        UserGameState gs = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        if (gs == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gs;
    }

    /** 步骤②④⑪：全部个股 prev_close 双写、连板统计、三连板停牌与 OHL 重置（停牌股冻结），返回股价索引。 */
    private Map<Long, UserStockState> settleStocks(long userId, int day, QuoteBoard board) {
        Map<Long, UserStockState> stateByStock = new LinkedHashMap<>();
        List<UserStockState> states = userStockStateMapper.selectList(
                new LambdaQueryWrapper<UserStockState>()
                        .eq(UserStockState::getUserId, userId)
                        .orderByAsc(UserStockState::getStockId));
        for (UserStockState state : states) {
            Quote quote = board.quote(state.getStockId());
            boolean wasHalted = quote.isHalted();
            // 收涨停判定必须先于 setPrevClose：limitUpPrice 基于昨日 prevClose；
            // 收盘价被盘中钳制不超涨停，!canBuy() 即收于涨停
            boolean closedAtLimitUp = !wasHalted && !quote.canBuy();
            double close = state.getCurrentPrice().doubleValue();
            // 当日涨跌幅必须在 setPrevClose 双写之前计算：双写后 current==prevClose 恒为 0
            double prevCloseBefore = state.getPrevClose().doubleValue();
            double dayChangePct = prevCloseBefore > 0 ? (close - prevCloseBefore) / prevCloseBefore : 0.0;
            state.setDayChangePct(dayChangePct);
            quote.setLastClosePct(dayChangePct);
            state.setPrevClose(state.getCurrentPrice());
            quote.setPrevClose(close);
            if (!wasHalted) {
                int streak = closedAtLimitUp ? quote.getLimitUpStreak() + 1 : 0;
                state.setLimitUpStreak(streak);
                quote.setLimitUpStreak(streak);
                state.setOpenPrice(state.getCurrentPrice());
                state.setHighPrice(state.getCurrentPrice());
                state.setLowPrice(state.getCurrentPrice());
                quote.setOpen(close);
                quote.setHigh(close);
                quote.setLow(close);
                if (streak >= 3) {
                    state.setHalted(true);
                    state.setHaltUntilDay(day + 2);
                    quote.setHalted(true);
                    notify(userId, day, "停牌核查",
                            StockDefs.byId(state.getStockId()).name() + "连续三日涨停，交易所启动停牌核查。",
                            state.getStockId());
                }
            }
            stateByStock.put(state.getStockId(), state);
            userStockStateMapper.updateById(state);
        }
        return stateByStock;
    }

    /** 步骤③：T+1 解冻，次日持仓全部可卖。 */
    private List<UserPosition> unfreezeT1(long userId) {
        List<UserPosition> positions = userPositionMapper.selectList(
                new LambdaQueryWrapper<UserPosition>().eq(UserPosition::getUserId, userId));
        for (UserPosition position : positions) {
            position.setAvailableShares(position.getShares());
            userPositionMapper.updateById(position);
        }
        return positions;
    }

    /** 步骤⑤：对正负债计提当日利息并落 INTEREST 流水（空负债跳过）。 */
    private void accrueInterest(long userId, SysUser user, int day) {
        BigDecimal debt = user.getDebt();
        if (debt == null || debt.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal interest = debt.multiply(BigDecimal.valueOf(GameConstants.ANNUAL_INTEREST))
                .divide(BigDecimal.valueOf(GameConstants.DAYS_PER_YEAR), 2, RoundingMode.HALF_UP);
        debt = debt.add(interest);
        user.setDebt(debt);
        sysUserMapper.updateById(user);
        LoanRecord record = new LoanRecord();
        record.setUserId(userId);
        record.setLoanType("INTEREST");
        record.setAmount(interest);
        record.setDebtAfter(debt);
        record.setGameDay(day);
        loanRecordMapper.insert(record);
    }

    /** 收盘总市值：收盘价 × 持股数求和。 */
    private BigDecimal marketValue(List<UserPosition> positions, Map<Long, UserStockState> stateByStock) {
        BigDecimal marketValue = BigDecimal.ZERO;
        for (UserPosition position : positions) {
            BigDecimal price = stateByStock.get(position.getStockId()).getCurrentPrice();
            marketValue = marketValue.add(price.multiply(BigDecimal.valueOf(position.getShares())));
        }
        return marketValue;
    }

    /** 指数日涨跌幅：昨收指数取前日快照，无前日快照按 INITIAL_INDEX 基期（spec §3 结算⑥）。 */
    private double indexDayPct(long userId, int day, UserGameState gs) {
        UserDailySnapshot prevSnapshot = userDailySnapshotMapper.selectOne(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .eq(UserDailySnapshot::getGameDay, day - 1));
        double prevIndex = prevSnapshot != null ? prevSnapshot.getIndexValue() : GameConstants.INITIAL_INDEX;
        return (gs.getIndexValue() / prevIndex - 1) * 100;
    }

    /** 日收益率：昨净资产取 prevTotalAsset，首日为空按初始资金 10 万基准。 */
    private static double dayReturnPctOf(UserGameState gs, BigDecimal netAsset) {
        BigDecimal prevTotal = gs.getPrevTotalAsset() != null
                ? gs.getPrevTotalAsset() : BigDecimal.valueOf(GameConstants.INITIAL_BALANCE);
        return (netAsset.doubleValue() / prevTotal.doubleValue() - 1) * 100;
    }

    /** 步骤⑥：日收益评价并推消息，返回日收益率。 */
    private double notifyDailyReview(long userId, int day, UserGameState gs, BigDecimal netAsset, double indexDayPct) {
        double dayReturnPct = dayReturnPctOf(gs, netAsset);
        notify(userId, day, "日收益评价",
                "今日净资产 " + money(netAsset) + " 元，收益率 " + pct(dayReturnPct) + "%；大盘 "
                        + pct(indexDayPct) + "%，您" + (dayReturnPct >= indexDayPct ? "跑赢" : "跑输")
                        + "大盘 " + pct(Math.abs(dayReturnPct - indexDayPct)) + " 个百分点。",
                null);
        return dayReturnPct;
    }

    /** 步骤⑦：按净资产评段位（spec §7.7），升档递烟、降档慰问、持平沉默。 */
    private void assessTier(long userId, int day, UserGameState gs, BigDecimal netAsset) {
        String targetTier = tierOf(netAsset.doubleValue());
        if (targetTier.equals(gs.getTier())) {
            return;
        }
        String tierName = TIER_NAMES.get(TIER_ORDER.indexOf(targetTier));
        if (TIER_ORDER.indexOf(targetTier) > TIER_ORDER.indexOf(gs.getTier())) {
            notify(userId, day, "段位晋升：" + tierName,
                    "净资产迈上新台阶，您晋升为" + tierName + "，营业部主任亲自给您递烟。", null);
        } else {
            notify(userId, day, "段位回落：" + tierName,
                    "您已跌回" + tierName + "室，张大妈表示慰问。", null);
        }
        gs.setTier(targetTier);
    }

    /** 按净资产评段位：≥100 万牛散、≥60 万游资、≥30 万大户、≥15 万中户，否则散户（spec §7.7）。 */
    private String tierOf(double netAsset) {
        for (int i = TIER_THRESHOLDS.length - 1; i >= 1; i--) {
            if (netAsset >= TIER_THRESHOLDS[i]) {
                return TIER_ORDER.get(i);
            }
        }
        return TIER_ORDER.get(0);
    }

    /** 段位代码转中文名（终局结算页用，与 TIER_ORDER/TIER_NAMES 同序映射）。 */
    public static String tierNameOf(String tier) {
        return TIER_NAMES.get(TIER_ORDER.indexOf(tier));
    }

    /** 步骤⑧：每日快照落库（NPC 资产 JSON 由 NpcService 生成，写入 npc_assets_json）。 */
    private void insertSnapshot(long userId, int day, UserGameState gs, BigDecimal cash, BigDecimal marketValue,
                                BigDecimal debt, BigDecimal netAsset, double dayReturnPct, double indexDayPct,
                                String npcJson) {
        UserDailySnapshot snapshot = new UserDailySnapshot();
        snapshot.setUserId(userId);
        snapshot.setGameDay(day);
        snapshot.setCash(cash);
        snapshot.setMarketValue(marketValue);
        snapshot.setDebt(debt);
        snapshot.setNetAsset(netAsset);
        snapshot.setDayReturnPct(dayReturnPct);
        snapshot.setIndexValue(gs.getIndexValue());
        snapshot.setIndexDayPct(indexDayPct);
        snapshot.setNpcAssetsJson(npcJson);
        userDailySnapshotMapper.insert(snapshot);
    }

    /** 步骤⑫：状态落位——收官日 FINISHED/NORMAL_FINISH 不发通知，否则 CLOSED 并推收盘结算通知。 */
    private void closeOutDay(long userId, int day, UserGameState gs, BigDecimal netAsset, double dayReturnPct) {
        if (day >= GameConstants.TOTAL_DAYS) {
            gs.setMarketState(MarketState.FINISHED.name());
            gs.setEndReason("NORMAL_FINISH");
            gs.setEndDay(day);
        } else {
            gs.setMarketState(MarketState.CLOSED.name());
            notify(userId, day, "收盘结算",
                    "第 " + day + " 个交易日收盘：净资产 " + money(netAsset) + " 元，日收益 "
                            + pct(dayReturnPct) + "%。",
                    null);
        }
    }

    /** 金额格式化：2 位 HALF_UP，规避 Locale 分组符。 */
    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 百分比格式化：2 位 HALF_UP，规避 Locale 分组符。 */
    private static String pct(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 系统通知落库（字段惯例同 detectEraSwitchAndNotify）。 */
    private void notify(long userId, int day, String title, String content, Long stockId) {
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource("SYSTEM");
        message.setType("NOTIFY");
        message.setTitle(title);
        message.setContent(content);
        message.setStockId(stockId);
        message.setIsRead(false);
        message.setGameDay(day);
        userMessageMapper.insert(message);
    }
}
