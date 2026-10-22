package com.holdhundredyear.event;

import com.holdhundredyear.game.constants.EventSkeletons;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.enums.SealState;
import com.holdhundredyear.market.engine.PriceShock;
import com.holdhundredyear.market.entity.GameEvent;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.mapper.GameEventMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主力剧本引擎（Task 8.5，spec §7.1）：由 {@link EventFactory#maybeRoll} 的事件出现闸落空
 * 分支（35%）内接入 {@link #maybeStart}，随后每跳由 {@link com.holdhundredyear.market.WorldTickService}
 * 调 {@link #tick} 推进，价格影响一律经 {@link com.holdhundredyear.market.engine.ShockQueue}
 * 入队 PriceShock 衰减落地，不直接改价。
 *
 * <p>三类剧本：
 * <ul>
 *   <li>SHAKEOUT 洗盘吸筹（净升，完成创出新高）：恐慌下砸 → 磨底 → 拉升，滞后仍不足则以
 *       小幅正冲击上探（含兜底轮）直至 current &gt; priorPeak；</li>
 *   <li>PUMP_DUMP 拉高出货（净跌，完成破位）：放量拉升 → 出货震荡 → 崩盘，滞后仍不足则以
 *       小幅负冲击下探（含兜底轮）直至 current &lt; launchPrice；</li>
 *   <li>SKY_EARTH 天地板（单跳涨停→跌停）：一次性 -99% ONCE 冲击，价格被钳到跌停价，启动即 done。</li>
 * </ul>
 *
 * <p>确定性协议（P5 快进等价）：进程内维护每用户至多一条活跃剧本（防刷屏）——
 * {@code Map<Long, MainScriptState>}（key=userId）。剧本全部参数（阶段时长/总幅/目标股）在
 * {@link #maybeStart} 启动时用入参 rng 一次性掷出并随状态留存，{@link #tick} 零掷骰——
 * 逐跳推进与快进（同 tick 序列同 rng）逐跳一致。MainScriptState 为内存态不持久化，
 * 重开由 {@link #clearUserState} 移除。
 */
@Service
public class MainScriptService {

    /** 洗盘/出货完成判定不足时的兜底单轮冲击跳数（保证单轮多数能在当日消耗）。 */
    private static final int TOPUP_TICKS = 8;
    /** 兜底单轮总幅（0.05 = +5% 或 -5%）；足够抬/压越过完成参考价，且远强于掩护 doTickOne 的本身漂移。 */
    private static final double TOPUP_MAG = 0.05;
    /** 兜底轮数上限（防极端钳制下不收敛；+5%×50 轮足破一切参考价，正常路径远用不完）。 */
    private static final int MAX_TOPUP = 50;

    private final MessageService messageService;
    private final GameEventMapper gameEventMapper;
    private final UserMessageMapper userMessageMapper;

    /** 每用户当前剧本状态（key=userId）。 */
    private final Map<Long, MainScriptState> scripts = new ConcurrentHashMap<>();

    public MainScriptService(MessageService messageService,
                             GameEventMapper gameEventMapper, UserMessageMapper userMessageMapper) {
        this.messageService = messageService;
        this.gameEventMapper = gameEventMapper;
        this.userMessageMapper = userMessageMapper;
    }

    /** 剧本类型（权重：洗盘 0.40 / 出货 0.35 / 天地板 0.15 / 尾盘偷袭 0.10）。 */
    enum ScriptKind { SHAKEOUT, PUMP_DUMP, SKY_EARTH, TAIL_WHIP }

    /** 剧本阶段。SHAKEOUT/PUMP_DUMP 共用三阶段推进 + 完成不足时的 TOPUP 兜底；SKY_EARTH 单跳即了。 */
    private enum Stage { P1, P2, P3, TOPUP }

    /** 剧本内存状态：启动时一次掷定的参数全部留存，tick 推进零掷骰（P5 快进等价）。 */
    static class MainScriptState {
        final ScriptKind type;
        final long stockId;
        Stage stage;
        int stageTicksLeft;
        final int n1, n2, n3;
        final double mag1, mag3;
        final double launchPrice; // PUMP_DUMP 启动价
        final double priorPeak;   // SHAKEOUT 启动时高点
        final boolean positive;   // SHAKEOUT true（完成需 up）/ PUMP_DUMP false（完成需 down）
        int topUpRounds;
        boolean done;
        /** 剧本配套消息（启动时一次选定，tick 零掷骰）：开盘传闻 + 收官追报。 */
        Long openingMessageId;
        String openingTitle;
        String openingContent;
        String followupContent;
        String truthValue;

        MainScriptState(ScriptKind type, long stockId, Stage stage, int n1, int n2, int n3,
                        double mag1, double mag3, double launchPrice, double priorPeak,
                        boolean positive, boolean done) {
            this.type = type;
            this.stockId = stockId;
            this.stage = stage;
            this.stageTicksLeft = n1;
            this.n1 = n1;
            this.n2 = n2;
            this.n3 = n3;
            this.mag1 = mag1;
            this.mag3 = mag3;
            this.launchPrice = launchPrice;
            this.priorPeak = priorPeak;
            this.positive = positive;
            this.done = done;
        }

        /** 剧本是否已收官（测试与 {@link #maybeStart} 活跃判定共用）。 */
        boolean isDone() {
            return done;
        }
    }

    /**
     * 剧本启动入口（EventFactory 事件闸落空分支调用）：已有活跃剧本返回 false；否则随机挑一只
     * 非停牌股、按权重掷剧本类型与全部阶段参数、写 game_event 与用户消息、入首阶段 shock。
     * rng 由调用方传入且仅只在启动成功时消费（活跃时先判返回，零消费，保证确定性）。
     */
    public boolean maybeStart(long userId, UserGameState gs, QuoteBoard board, Random rng) {
        MainScriptState active = scripts.get(userId);
        if (active != null && !active.done) {
            return false;
        }
        List<Long> candidates = board.getQuotes().values().stream()
                .filter(q -> !q.isHalted())
                .map(Quote::getStockId)
                .toList();
        if (candidates.isEmpty()) {
            return false;
        }
        long stockId = candidates.get(rng.nextInt(candidates.size()));
        ScriptKind kind = rollKind(rng);
        // 尾盘偷袭只允许下午最后约 12 跳启动；不满足就退回普通剧本（多消费一次掷骰，仍确定性）
        if (kind == ScriptKind.TAIL_WHIP
                && board.getTickIndex() < GameConstants.TOTAL_TICKS - 12) {
            kind = rng.nextBoolean() ? ScriptKind.SHAKEOUT : ScriptKind.PUMP_DUMP;
        }
        return startFor(userId, gs, board, rng, kind, stockId);
    }

    /** 强制启动指定类型与目标股的剧本（测试探针：自然启动耦合单据置困难时可控触发）。 */
    boolean startFor(long userId, UserGameState gs, QuoteBoard board, Random rng,
                     ScriptKind kind, long stockId) {
        MainScriptState st = buildState(kind, stockId, board, rng);
        scripts.put(userId, st);
        prepareOpeningNews(st, rng);
        writeOpening(userId, gs, st);
        if (kind == ScriptKind.SKY_EARTH) {
            // 真正的天地板/地天板：先顶到一端并解除旧封板，再在下一跳用 ±99% 砸/拉到另一端
            Quote quote = board.quote(stockId);
            quote.setSealed(SealState.NONE);
            quote.setSealTicksLeft(0);
            if (st.positive) {
                quote.setCurrent(quote.limitDownPrice());
                quote.setLow(Math.min(quote.getLow(), quote.limitDownPrice()));
                board.getShocks().enqueue(new PriceShock(stockId, false, 0.99, 1, PriceShock.Decay.ONCE));
            } else {
                quote.setCurrent(quote.limitUpPrice());
                quote.setHigh(Math.max(quote.getHigh(), quote.limitUpPrice()));
                board.getShocks().enqueue(new PriceShock(stockId, false, -0.99, 1, PriceShock.Decay.ONCE));
            }
            st.done = true;
        } else {
            board.getShocks().enqueue(new PriceShock(stockId, false, st.mag1, st.n1, PriceShock.Decay.LINEAR));
        }
        return true;
    }

    /** 每跳推进活跃剧本的阶段计数（零掷骰）：到 0 切下一阶段并入 shock；末阶段判完成，不足走兜底轮。 */
    public void tick(long userId, UserGameState gs, QuoteBoard board) {
        MainScriptState st = scripts.get(userId);
        if (st == null || st.done) {
            return;
        }
        if (board.quote(st.stockId).isHalted()) {
            finish(userId, gs, st); // 目标停牌：终止剧本，但要把 PENDING 传闻收尾，不能留悬案
            return;
        }
        st.stageTicksLeft--;
        if (st.stageTicksLeft > 0) {
            return;
        }
        switch (st.stage) {
            case P1 -> {
                if (st.type == ScriptKind.TAIL_WHIP) {
                    st.done = true; // 尾盘偷袭只做一波，无后续阶段、无消息
                } else {
                    st.stage = Stage.P2;
                    st.stageTicksLeft = st.n2;
                }
            }
            case P2 -> { st.stage = Stage.P3; st.stageTicksLeft = st.n3;
                board.getShocks().enqueue(new PriceShock(st.stockId, false, st.mag3, st.n3,
                        PriceShock.Decay.LINEAR)); }
            case P3 -> {
                if (completed(st, board)) {
                    finish(userId, gs, st);
                } else {
                    enterTopUp(st, board);
                }
            }
            case TOPUP -> {
                if (completed(st, board)) {
                    finish(userId, gs, st);
                } else if (st.topUpRounds < MAX_TOPUP) {
                    enterTopUp(st, board);
                } else {
                    finish(userId, gs, st);
                }
            }
        }
    }

    /** 完成判定：SHAKEOUT 需 current &gt; priorPeak，PUMP_DUMP 需 current &lt; launchPrice。 */
    private boolean completed(MainScriptState st, QuoteBoard board) {
        double cur = board.quote(st.stockId).getCurrent();
        return st.positive ? cur > st.priorPeak : cur < st.launchPrice;
    }

    /** 进入兜底轮：向完成方向小幅冲击，直到完成判定满足（含轮数上限兜底）。 */
    private void enterTopUp(MainScriptState st, QuoteBoard board) {
        st.stage = Stage.TOPUP;
        st.topUpRounds++;
        st.stageTicksLeft = TOPUP_TICKS;
        double dir = st.positive ? 1.0 : -1.0;
        board.getShocks().enqueue(new PriceShock(st.stockId, false, dir * TOPUP_MAG,
                TOPUP_TICKS, PriceShock.Decay.LINEAR));
    }

    /** 剧本收官：洗盘/出货用权威辟谣收尾（原传闻翻成 DENIED）；尾盘偷袭与天地板不额外发消息。 */
    private void finish(long userId, UserGameState gs, MainScriptState st) {
        st.done = true;
        if (st.type == ScriptKind.TAIL_WHIP || st.openingMessageId == null) {
            return;
        }
        userMessageMapper.update(null, new LambdaUpdateWrapper<UserMessage>()
                .eq(UserMessage::getId, st.openingMessageId)
                .set(UserMessage::getTruthStatus, st.truthValue));
        UserMessage followup = new UserMessage();
        followup.setUserId(userId);
        followup.setSource("AUTHORITY");
        followup.setTruthStatus("CONFIRMED");
        followup.setType("EVENT");
        followup.setTitle("【辟谣】" + st.openingTitle);
        followup.setContent(st.followupContent == null ? "相关传闻已有定论。" : st.followupContent);
        followup.setStockId(st.stockId);
        followup.setFollowupOf(st.openingMessageId);
        followup.setIsRead(false);
        followup.setGameDay(gs.getCurrentDay());
        followup.setCreatedAt(LocalDateTime.now());
        messageService.publish(followup);
    }

    /** 重开/登出时移除该用户剧本状态（内存态，不持久化）。 */
    public void clearUserState(long userId) {
        scripts.remove(userId);
    }

    /** 测试探针：取该用户剧本状态（无则 null）。 */
    MainScriptState getState(long userId) {
        return scripts.get(userId);
    }

    /** 启动时掷定剧本全部阶段参数（决策与时长一次掷出，tick 零掷骰）。 */
    private MainScriptState buildState(ScriptKind kind, long stockId, QuoteBoard board, Random rng) {
        Quote q = board.quote(stockId);
        switch (kind) {
            case SHAKEOUT -> {
                double priorPeak = Math.max(q.getHigh(), q.getCurrent());
                int n1 = rng.nextInt(3, 9);                          // 恐慌 3~8 跳
                double mag1 = -(0.04 + 0.05 * rng.nextDouble());     // 下砸 -4%~-9%
                int n2 = rng.nextInt(5, 16);                         // 磨底 5~15 跳
                int n3 = rng.nextInt(10, 21);                        // 拉升 10~20 跳
                double mag3 = 0.10 + 0.08 * rng.nextDouble();        // 上拉 +10%~+18%
                return new MainScriptState(kind, stockId, Stage.P1, n1, n2, n3,
                        mag1, mag3, 0.0, priorPeak, true, false);
            }
            case PUMP_DUMP -> {
                double launch = q.getCurrent();
                int n1 = rng.nextInt(5, 13);                         // 拉升 5~12 跳
                double mag1 = 0.15 + 0.10 * rng.nextDouble();        // +15%~+25%
                int n2 = rng.nextInt(5, 11);                         // 出货震荡 5~10 跳
                int n3 = rng.nextInt(2, 6);                          // 崩盘 2~5 跳
                double mag3 = -(0.25 + 0.10 * rng.nextDouble());     // -25%~-35%，X3&gt;X1 净跌
                return new MainScriptState(kind, stockId, Stage.P1, n1, n2, n3,
                        mag1, mag3, launch, 0.0, false, false);
            }
            case TAIL_WHIP -> {
                int remaining = Math.max(1, GameConstants.TOTAL_TICKS - board.getTickIndex());
                int n1 = Math.min(rng.nextInt(3, 6), remaining);
                double mag = 0.05 + 0.05 * rng.nextDouble();
                if (rng.nextBoolean()) {
                    mag = -mag;
                }
                return new MainScriptState(kind, stockId, Stage.P1, n1, 0, 0,
                        mag, 0.0, 0.0, 0.0, mag > 0, false);
            }
            default -> {
                boolean up = rng.nextBoolean(); // 天地板 / 地天板各半
                return new MainScriptState(kind, stockId, Stage.P1, 1, 1, 1,
                        up ? 0.99 : -0.99, 0.0, 0.0, 0.0, up, false);
            }
        }
    }

    /**
     * 启动时选定剧本配套传闻与追报（tick 阶段零掷骰）。文案取 event-copy.txt 的
     * rumor-short-report / rumor-restructure / rumor-halt-check，不再出现"主力洗盘开始"这类
     * 直接剧透的元消息——玩家应该先看到一条像样的传闻，再自己判断真假。
     */
    private void prepareOpeningNews(MainScriptState st, Random rng) {
        if (st.type == ScriptKind.TAIL_WHIP) {
            return;
        }
        String code = switch (st.type) {
            case SHAKEOUT -> "rumor-short-report";
            case PUMP_DUMP -> "rumor-restructure";
            default -> "rumor-halt-check";
        };
        EventSkeleton skeleton = EventSkeletons.ALL.stream()
                .filter(s -> code.equals(s.code()))
                .findFirst()
                .orElse(null);
        String name = StockDefs.byId(st.stockId).name();
        if (skeleton == null) {
            st.openingTitle = "传" + name + "有重大事项待公布";
            st.openingContent = "股吧有人称" + name + "将有重大事项，公司尚未回应。";
            st.followupContent = "相关传闻已有定论。";
        } else {
            EventSkeleton.Variant variant = skeleton.variants().get(rng.nextInt(skeleton.variants().size()));
            st.openingTitle = EventFactory.fillSlots(variant.title(), name, rng);
            st.openingContent = EventFactory.fillSlots(variant.text(), name, rng);
            st.followupContent = skeleton.followupTexts().isEmpty() ? "相关传闻已有定论。"
                    : EventFactory.fillSlots(skeleton.followupTexts()
                    .get(rng.nextInt(skeleton.followupTexts().size())), name, rng);
        }
        st.truthValue = "DENIED";
    }

    /** 剧本开启落库：game_event（scriptType=类型）+ 一条 RUMOR/PENDING 传闻（不剧透主力）。 */
    private void writeOpening(long userId, UserGameState gs, MainScriptState st) {
        if (st.type == ScriptKind.TAIL_WHIP || st.openingTitle == null) {
            return;
        }
        String eventType = st.type == ScriptKind.PUMP_DUMP ? "GOOD" : "BAD";
        double impactPct = st.type == ScriptKind.SKY_EARTH ? 0.99 : Math.abs(st.mag1);
        LocalDateTime now = LocalDateTime.now();
        GameEvent event = new GameEvent();
        event.setUserId(userId);
        event.setStockId(st.stockId);
        event.setScope("STOCK");
        event.setScriptType(st.type.name());
        event.setEventType(eventType);
        event.setImpactPattern("NONE");
        event.setTitle(st.openingTitle);
        event.setContent(st.openingContent);
        event.setImpactPct(impactPct);
        event.setGameDay(gs.getCurrentDay());
        event.setCreatedAt(now);
        gameEventMapper.insert(event);

        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource("RUMOR");
        // 天地板是单跳事件，没有机会走"传闻→辟谣"生命周期；不给三态，避免永远停在未证实
        message.setTruthStatus(st.type == ScriptKind.SKY_EARTH ? null : "PENDING");
        message.setType("EVENT");
        message.setTitle(st.openingTitle);
        message.setContent(st.openingContent);
        message.setStockId(st.stockId);
        message.setIsRead(false);
        message.setGameDay(gs.getCurrentDay());
        message.setCreatedAt(now);
        messageService.publish(message);
        st.openingMessageId = message.getId();
    }

    /** 权重轮盘：SHAKEOUT 0.40 / PUMP_DUMP 0.35 / SKY_EARTH 0.15 / TAIL_WHIP 0.10。 */
    private static ScriptKind rollKind(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.40) {
            return ScriptKind.SHAKEOUT;
        }
        if (r < 0.75) {
            return ScriptKind.PUMP_DUMP;
        }
        if (r < 0.90) {
            return ScriptKind.SKY_EARTH;
        }
        return ScriptKind.TAIL_WHIP;
    }
}