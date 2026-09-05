package com.holdhunderdyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.holdhunderdyear.game.constants.EventSkeletons;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.constants.StockDefs.StockDef;
import com.holdhunderdyear.game.model.enums.EraCode;
import com.holdhunderdyear.market.entity.GameEvent;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserPosition;
import com.holdhunderdyear.market.engine.PriceShock;
import com.holdhunderdyear.market.mapper.GameEventMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserPositionMapper;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.trade.CorporateActionService;
import com.holdhunderdyear.world.WorldRandom;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 事件工厂（Phase 8.1，spec §8）：每日盘中掷骰事件的唯一入口——由
 * {@link com.holdhunderdyear.market.WorldTickService#tickOne} 在每跳末尾调用
 * {@link #maybeRoll}。单盐确定性：rng = WorldRandom.of(worldSeed,
 * "event{day}.{tickIndex}")，全部掷骰集中在入队时，PendingAction 入队即预填
 * 文案/参数/真伪值，flush 时零掷骰。
 *
 * <p>calf 映射：七种冲击模式在入队时展开为 ShockQueue 的 PriceShock 与/或待到期
 * 定时器（TRUTH_REVEAL/REVEAL_SHOCK/REVERT_SHOCK/CHAIN_SHOCK）。TIMED 结构经
 * {@link #flushDueActions} 在每跳开头结算（到期翻面、追 followup 或入 shock）。
 *
 * <p>企业行动（Task 9.1）：掷出 fund-st-hat / fund-st-unhat / fund-turnaround /
 * fund-high-transfer / fund-dividend 这些 pool① 个股骨架时，在写库之后、入 shock 之前按
 * skeleton.code() 分派给 {@link CorporateActionService} 做真实的戴帽/摘帽/除权/分红
 * （事件氛围文案与 7 模式冲击照常保留，真实机制动作叠加其上）。
 *
 * <p>并发协议：正常读写都在 per-user 世界锁内串行（tickOne / restart），
 * ConcurrentHashMap 与 synchronizedList 仅为防御性兜底；遍历统一持同一 list 监视器。
 * PendingAction 不持久化：重启即丢（spec 授权，followup 不了了之），重开时由
 * {@link com.holdhunderdyear.market.GameEndService} 调 {@link #clearPending} 清理。
 */
@Service
public class EventFactory {

    /** 事件来源白名单：仅这些来源可被随机掷出（FUN/NPC/SYSTEM 由剧本与系统逻辑使用）。 */
    private static final Set<EventSkeleton.Source> ROLLABLE_SOURCES = EnumSet.of(
            EventSkeleton.Source.AUTHORITY, EventSkeleton.Source.MEDIA,
            EventSkeleton.Source.RUMOR, EventSkeleton.Source.INSIDER);

    /** 需 TRUTH_REVEAL 定时器的来源：消息先以 PENDING 落库，到期翻面并追 followup。 */
    private static final Set<EventSkeleton.Source> PENDING_SOURCES = EnumSet.of(
            EventSkeleton.Source.MEDIA, EventSkeleton.Source.RUMOR, EventSkeleton.Source.INSIDER);

    /** 事件出现闸：掷骰通过率（8.5 主力剧本接管此落空分支，8.1 不处理）。 */
    private static final double EVENT_GATE = 0.65;

    /** 槽位解析：白名单 {stock}/{amount}/{user}。 */
    private static final Pattern SLOT_PATTERN = Pattern.compile("\\{(stock|amount|user)\\}");

    /** 股友昵称字符集：[A-Z0-9]。 */
    private static final String USER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final UserMessageMapper userMessageMapper;
    private final GameEventMapper gameEventMapper;
    private final UserPositionMapper userPositionMapper;
    private final MainScriptService mainScriptService;
    private final CorporateActionService corporateActionService;

    /** userId → 到期待结算动作（TRUTH_REVEAL 每事件一条；冲击类每股一条）。 */
    private final ConcurrentHashMap<Long, List<PendingAction>> pendingActions = new ConcurrentHashMap<>();

    public EventFactory(UserMessageMapper userMessageMapper,
                        GameEventMapper gameEventMapper,
                        UserPositionMapper userPositionMapper,
                        MainScriptService mainScriptService,
                        CorporateActionService corporateActionService) {
        this.userMessageMapper = userMessageMapper;
        this.gameEventMapper = gameEventMapper;
        this.userPositionMapper = userPositionMapper;
        this.mainScriptService = mainScriptService;
        this.corporateActionService = corporateActionService;
    }

    /**
     * 每跳事件掷骰入口（WorldTickService 在 setTickIndex 自增后调用，tickIndex 已+1）。
     * 流程：先结算到期待办 → 未到下次掷骰点直接返回 → 建 rng 并推进下次掷骰点
     * （顺序固定保证确定性）→ 事件出现闸 → 按纪元/来源/持仓过滤池 → 权重轮盘选骨架
     * → 掷变体/目标/幅度 → 填槽 → 写库（user_message + game_event）→ 按模式入
     * shock/定时器。
     */
    public void maybeRoll(long userId, UserGameState gs, QuoteBoard board) {
        long absTick = (long) gs.getCurrentDay() * GameConstants.TOTAL_TICKS + board.getTickIndex();
        flushDueActions(userId, gs, board, absTick);
        if (absTick < board.getNextRollAbsTick()) {
            return;
        }
        Random rng = WorldRandom.of(gs.getWorldSeed(),
                "event" + gs.getCurrentDay() + "." + board.getTickIndex());
        board.setNextRollAbsTick(absTick + rng.nextInt(10, 21));
        if (rng.nextDouble() >= EVENT_GATE) {
            // 事件出现闸落空分支（35%）由 8.5 主力剧本接管（零剧本待命时入队一个新剧本）
            mainScriptService.maybeStart(userId, gs, board, rng);
            return;
        }

        EraCode era = board.getCurrentEra() != null ? board.getCurrentEra().code() : null;
        Set<EraCode> allowed = era != null ? EnumSet.of(era) : EnumSet.noneOf(EraCode.class);
        Set<Long> holdings = holdingStockIds(userId);
        List<EventSkeleton> pool = new ArrayList<>();
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            if (!ROLLABLE_SOURCES.contains(skeleton.source())) {
                continue;
            }
            if (skeleton.source() == EventSkeleton.Source.INSIDER && holdings.isEmpty()) {
                continue;
            }
            if (!skeleton.eras().isEmpty() && (era == null || !skeleton.eras().contains(era))) {
                continue;
            }
            pool.add(skeleton);
        }
        if (pool.isEmpty()) {
            return;
        }
        EventSkeleton skeleton = pickSkeleton(pool, allowed, rng);

        EventSkeleton.Variant variant = skeleton.variants().get(rng.nextInt(skeleton.variants().size()));
        boolean marketWide = skeleton.scope() == EventSkeleton.Scope.MARKET;
        List<StockDef> candidates = marketWide ? List.of() : candidatesFor(variant.sector(), board);
        Long targetStockId = null;
        if (skeleton.scope() == EventSkeleton.Scope.STOCK) {
            if (candidates.isEmpty()) {
                return;
            }
            targetStockId = pickTargetStock(skeleton, variant, candidates, holdings, rng);
        } else if (skeleton.scope() == EventSkeleton.Scope.SECTOR && candidates.isEmpty()) {
            return;
        }

        double lo = skeleton.impactRange()[0];
        double hi = skeleton.impactRange()[1];
        double mag = lo + (hi - lo) * rng.nextDouble();

        String stockName = targetStockId != null ? StockDefs.byId(targetStockId).name() : null;
        String title = fillSlots(variant.title(), stockName, rng);
        String content = fillSlots(variant.text(), stockName, rng);
        String followupContent = null;
        if (!skeleton.followupTexts().isEmpty()) {
            followupContent = fillSlots(
                    skeleton.followupTexts().get(rng.nextInt(skeleton.followupTexts().size())),
                    stockName, rng);
        }

        // 模式相关掷骰（消费顺序固定，保证确定性）
        int driftTicks = 0;
        long revealDue = 0L;
        boolean confirmed = false;
        double revealPct = 0.0;
        long revertDue = 0L;
        double revertPct = 0.0;
        long chainDue1 = 0L;
        long chainDue2 = 0L;
        double chainPct1 = 0.0;
        double chainPct2 = 0.0;
        switch (skeleton.pattern()) {
            case SLOW_DRIFT -> driftTicks = rng.nextInt(skeleton.tickRange()[0], skeleton.tickRange()[1]);
            case BUY_RUMOR -> {
                driftTicks = rng.nextInt(skeleton.tickRange()[0], skeleton.tickRange()[1]);
                revealDue = absTick + rng.nextInt(90, 271);
                confirmed = rollTruth(skeleton, rng);
                revealPct = -mag * 0.8;
            }
            case OVERSHOOT_REVERT -> {
                revertDue = absTick + 90 + rng.nextInt(0, 21);
                revertPct = -mag * rng.nextDouble(0.3, 0.5);
            }
            case NEWS_FLIP -> {
                revealDue = absTick + rng.nextInt(90, 271);
                confirmed = rollTruth(skeleton, rng);
                double dir = confirmed ? Math.signum(mag) : -Math.signum(mag);
                if (rng.nextDouble() < 0.20) {
                    dir = -dir;
                }
                revealPct = dir * Math.abs(mag) * 0.4;
            }
            case CHAIN -> {
                chainPct1 = mag * rng.nextDouble(0.3, 0.5);
                chainDue1 = absTick + 90 + rng.nextInt(0, 45);
                chainPct2 = mag * rng.nextDouble(0.3, 0.5);
                chainDue2 = absTick + 180 + rng.nextInt(0, 45);
            }
            default -> { }
        }
        if (PENDING_SOURCES.contains(skeleton.source())
                && skeleton.pattern() != EventSkeleton.ImpactPattern.BUY_RUMOR
                && skeleton.pattern() != EventSkeleton.ImpactPattern.NEWS_FLIP) {
            revealDue = absTick + rng.nextInt(90, 271);
            confirmed = rollTruth(skeleton, rng);
        }

        // 写库：user_message + game_event（title/content 同消息）
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource(skeleton.source().name());
        message.setTruthStatus(truthStatusOf(skeleton.source()));
        message.setType("EVENT");
        message.setTitle(title);
        message.setContent(content);
        message.setStockId(targetStockId);
        message.setIsRead(false);
        message.setGameDay(gs.getCurrentDay());
        message.setCreatedAt(LocalDateTime.now());
        userMessageMapper.insert(message);

        GameEvent event = new GameEvent();
        event.setUserId(userId);
        event.setStockId(targetStockId);
        event.setScope(skeleton.scope().name());
        event.setScriptType("NONE");
        event.setEventType(mag > 0 ? "GOOD" : "BAD");
        event.setImpactPattern(skeleton.pattern().name());
        event.setTitle(title);
        event.setContent(content);
        event.setImpactPct(mag);
        event.setGameDay(gs.getCurrentDay());
        event.setCreatedAt(LocalDateTime.now());
        gameEventMapper.insert(event);

        // 企业行动接入（Task 9.1）：ST 戴帽/摘帽、10转10 除权、现金分红——对 target 股做真实
        // 世界行动（STOCK scope 才有 target；SECTOR/MARKET 骨架不命中，null 守卫跳过）。
        // 分红额在同一 rng 追加一次 nextDouble（0.5~2.0），计入本事件固定的随机消费顺序。
        // gameDay 与消息落库的 game_day 同口径（user_message.game_day schema 非空）
        int gameDay = gs.getCurrentDay();
        if (targetStockId != null) {
            switch (skeleton.code()) {
                case "fund-st-hat" -> corporateActionService.hat(userId, board, targetStockId, gameDay);
                case "fund-st-unhat", "fund-turnaround" ->
                        corporateActionService.unhat(userId, board, targetStockId, gameDay);
                case "fund-high-transfer" ->
                        corporateActionService.split10to10(userId, board, targetStockId, gameDay);
                case "fund-dividend" -> corporateActionService.cashDividend(userId, board, targetStockId,
                        0.5 + 1.5 * rng.nextDouble(), gameDay);
                default -> { }
            }
        }

        List<Long> shockTargets = marketWide ? List.of(0L)
                : skeleton.scope() == EventSkeleton.Scope.SECTOR
                        ? candidates.stream().map(StockDef::id).toList()
                        : List.of(targetStockId);
        List<PendingAction> pendings = pendingActions.computeIfAbsent(userId,
                key -> Collections.synchronizedList(new ArrayList<>()));
        // TRUTH_REVEAL 挂所有 PENDING 来源（MEDIA/RUMOR/INSIDER）事件，无论何种模式
        if (PENDING_SOURCES.contains(skeleton.source())) {
            pendings.add(new PendingAction(PendingKind.TRUTH_REVEAL, revealDue, message.getId(),
                    0L, false, 0.0, title, followupContent, confirmed ? "CONFIRMED" : "DENIED"));
        }
        switch (skeleton.pattern()) {
            case INSTANT -> enqueueShocks(board, shockTargets, marketWide, mag, 1, PriceShock.Decay.ONCE);
            case SPIKE_FADE -> {
                enqueueShocks(board, shockTargets, marketWide, mag, 3, PriceShock.Decay.LINEAR);
                enqueueShocks(board, shockTargets, marketWide, -mag, 9, PriceShock.Decay.EXP);
            }
            case SLOW_DRIFT -> enqueueShocks(board, shockTargets, marketWide, mag, driftTicks,
                    PriceShock.Decay.LINEAR);
            case BUY_RUMOR -> {
                enqueueShocks(board, shockTargets, marketWide, mag * 0.4, driftTicks, PriceShock.Decay.LINEAR);
                addShockPendings(pendings, PendingKind.REVEAL_SHOCK, shockTargets, marketWide,
                        revealDue, revealPct);
            }
            case OVERSHOOT_REVERT -> {
                enqueueShocks(board, shockTargets, marketWide, mag, 1, PriceShock.Decay.ONCE);
                addShockPendings(pendings, PendingKind.REVERT_SHOCK, shockTargets, marketWide,
                        revertDue, revertPct);
            }
            case NEWS_FLIP -> {
                enqueueShocks(board, shockTargets, marketWide, mag * 0.6, 1, PriceShock.Decay.ONCE);
                addShockPendings(pendings, PendingKind.REVEAL_SHOCK, shockTargets, marketWide,
                        revealDue, revealPct);
            }
            case CHAIN -> {
                enqueueShocks(board, shockTargets, marketWide, mag, 1, PriceShock.Decay.ONCE);
                addShockPendings(pendings, PendingKind.CHAIN_SHOCK, shockTargets, marketWide,
                        chainDue1, chainPct1);
                addShockPendings(pendings, PendingKind.CHAIN_SHOCK, shockTargets, marketWide,
                        chainDue2, chainPct2);
            }
        }
    }

    /** 重开/登出时清除该 userId 所有待办（防 followup 指向被删除消息）。 */
    public void clearPending(long userId) {
        pendingActions.remove(userId);
    }

    // ---- 静态纯函数（供统计测试）----

    /** 权重轮盘选骨架：先按纪元过滤（骨架 eras 为空=全纪元可用；allowed 为可用纪元集合）。 */
    static EventSkeleton pickSkeleton(List<EventSkeleton> pool, Set<EraCode> allowed, Random rng) {
        List<EventSkeleton> filtered = new ArrayList<>();
        for (EventSkeleton skeleton : pool) {
            if (skeleton.eras().isEmpty() || !Collections.disjoint(skeleton.eras(), allowed)) {
                filtered.add(skeleton);
            }
        }
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("event skeleton pool is empty after era filter");
        }
        double total = 0.0;
        for (EventSkeleton skeleton : filtered) {
            total += skeleton.weight();
        }
        double roll = rng.nextDouble() * total;
        for (EventSkeleton skeleton : filtered) {
            roll -= skeleton.weight();
            if (roll < 0) {
                return skeleton;
            }
        }
        return filtered.get(filtered.size() - 1);
    }

    /** 真伪钉点：AUTHORITY 恒真；其余以骨架 truthProb 掷点判断。 */
    static boolean rollTruth(EventSkeleton skeleton, Random rng) {
        if (skeleton.source() == EventSkeleton.Source.AUTHORITY) {
            return true;
        }
        return rng.nextDouble() < skeleton.truthProb();
    }

    /** 目标股轮盘：HOLDING_BIASED 时（持仓∩候选）权重 2、其余权重 1；ANY_STOCK 等权。
     *  variant 参数为签名兼容保留（后续板块路由扩展用），当前不参与。 */
    static long pickTargetStock(EventSkeleton skeleton, EventSkeleton.Variant variant,
                                List<StockDef> candidates, Set<Long> holdingStockIds, Random rng) {
        double total = 0.0;
        for (StockDef candidate : candidates) {
            total += weightOf(skeleton, candidate.id(), holdingStockIds);
        }
        double roll = rng.nextDouble() * total;
        for (StockDef candidate : candidates) {
            roll -= weightOf(skeleton, candidate.id(), holdingStockIds);
            if (roll < 0) {
                return candidate.id();
            }
        }
        return candidates.get(candidates.size() - 1).id();
    }

    /** 槽位填充：{stock}→stockName（仅 STOCK 骨架使用）、{amount}→随机金额、{user}→随机股友。 */
    static String fillSlots(String text, String stockName, Random rng) {
        Matcher matcher = SLOT_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "stock" -> stockName;
                case "amount" -> rollAmount(rng);
                default -> rollUser(rng);
            };
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 随机金额：0.3~80 亿均匀掷点，不足 1 亿折算为万。 */
    static String rollAmount(Random rng) {
        double v = rng.nextDouble(0.3, 80);
        return v >= 1 ? String.format("%.1f 亿", v) : String.format("%.0f 万", v * 10000);
    }

    /** 随机股友昵称："股友" + 6 位 [A-Z0-9]。 */
    static String rollUser(Random rng) {
        StringBuilder sb = new StringBuilder("股友");
        for (int i = 0; i < 6; i++) {
            sb.append(USER_CHARS.charAt(rng.nextInt(USER_CHARS.length())));
        }
        return sb.toString();
    }

    // ---- 私有辅助 ----

    /** 待办定时器种类：真伪翻面 / 报道反噬 / 冲高回落 / 连环补跌。 */
    private enum PendingKind { TRUTH_REVEAL, REVEAL_SHOCK, REVERT_SHOCK, CHAIN_SHOCK }

    /** 待到期动作：入队时预填全部参数与真伪值（flush 零掷骰）。 */
    private record PendingAction(PendingKind kind, long dueAbsTick, long messageId, long stockId,
                                 boolean marketWide, double pct, String title, String content,
                                 String truthValue) {
    }

    /** 每跳开头结算到期待办；尚未到期的保留。 */
    private void flushDueActions(long userId, UserGameState gs, QuoteBoard board, long absTick) {
        List<PendingAction> pendings = pendingActions.get(userId);
        if (pendings == null) {
            return;
        }
        List<PendingAction> due = new ArrayList<>();
        synchronized (pendings) {
            Iterator<PendingAction> it = pendings.iterator();
            while (it.hasNext()) {
                PendingAction action = it.next();
                if (action.dueAbsTick() <= absTick) {
                    due.add(action);
                    it.remove();
                }
            }
        }
        for (PendingAction action : due) {
            if (action.kind() == PendingKind.TRUTH_REVEAL) {
                flushTruthReveal(userId, gs, action);
            } else {
                board.getShocks().enqueue(new PriceShock(action.stockId(), action.marketWide(),
                        action.pct(), 1, PriceShock.Decay.ONCE));
            }
        }
    }

    /** TRUTH_REVEAL 结算：原消息 truth_status 翻面 + 追一条 followup（source 同原消息，
     *  title 加【证实】/【辟谣】前缀，content 为入队时预选文案；掷骰为零）。 */
    private void flushTruthReveal(long userId, UserGameState gs, PendingAction action) {
        userMessageMapper.update(null, new LambdaUpdateWrapper<UserMessage>()
                .eq(UserMessage::getId, action.messageId())
                .set(UserMessage::getTruthStatus, action.truthValue()));
        UserMessage original = userMessageMapper.selectById(action.messageId());
        if (original == null) {
            return;
        }
        UserMessage followup = new UserMessage();
        followup.setUserId(userId);
        followup.setSource(original.getSource());
        followup.setType("EVENT");
        followup.setTitle(("CONFIRMED".equals(action.truthValue()) ? "【证实】" : "【辟谣】") + action.title());
        followup.setContent(action.content());
        followup.setFollowupOf(action.messageId());
        followup.setIsRead(false);
        followup.setGameDay(gs.getCurrentDay());
        followup.setCreatedAt(LocalDateTime.now());
        userMessageMapper.insert(followup);
    }

    /** 冲击类待办：每股一条（MARKET 为 marketWide 单条）。 */
    private void addShockPendings(List<PendingAction> pendings, PendingKind kind, List<Long> stockIds,
                                  boolean marketWide, long due, double pct) {
        for (Long stockId : stockIds) {
            pendings.add(new PendingAction(kind, due, 0L, stockId, marketWide, pct, null, null, null));
        }
    }

    /** 立即入 shock 队列：STOCK/SECTOR 每股一条，MARKET 一条 marketWide。 */
    private void enqueueShocks(QuoteBoard board, List<Long> stockIds, boolean marketWide,
                               double pct, int ticks, PriceShock.Decay decay) {
        for (Long stockId : stockIds) {
            board.getShocks().enqueue(new PriceShock(stockId, marketWide, pct, ticks, decay));
        }
    }

    /** 消息 truth_status：AUTHORITY 恒 CONFIRMED；MEDIA/RUMOR/INSIDER 先 PENDING；其余 null。 */
    private static String truthStatusOf(EventSkeleton.Source source) {
        if (source == EventSkeleton.Source.AUTHORITY) {
            return "CONFIRMED";
        }
        return PENDING_SOURCES.contains(source) ? "PENDING" : null;
    }

    /** 该板块（sector=null 全板块）的非停牌候选股，停牌股不作冲击目标。 */
    private List<StockDef> candidatesFor(String sector, QuoteBoard board) {
        List<StockDef> candidates = new ArrayList<>();
        for (StockDef def : StockDefs.ALL) {
            if (sector != null && !def.sector().equals(sector)) {
                continue;
            }
            if (board.quote(def.id()).isHalted()) {
                continue;
            }
            candidates.add(def);
        }
        return candidates;
    }

    /** 该 userId 全部持仓股（shares>0 的 stockId）。 */
    private Set<Long> holdingStockIds(long userId) {
        return userPositionMapper.selectList(new LambdaQueryWrapper<UserPosition>()
                        .eq(UserPosition::getUserId, userId)
                        .gt(UserPosition::getShares, 0))
                .stream().map(UserPosition::getStockId).collect(Collectors.toUnmodifiableSet());
    }

    /** HOLDING_BIASED 时持仓权重 2、否则 1。 */
    private static double weightOf(EventSkeleton skeleton, long stockId, Set<Long> holdingStockIds) {
        return skeleton.target() == EventSkeleton.TargetSelector.HOLDING_BIASED
                && holdingStockIds.contains(stockId) ? 2.0 : 1.0;
    }
}