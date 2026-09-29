package com.holdhunderdyear.event;

import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.market.engine.PriceShock;
import com.holdhunderdyear.market.entity.GameEvent;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.GameEventMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主力剧本引擎（Task 8.5，spec §7.1）：由 {@link EventFactory#maybeRoll} 的事件出现闸落空
 * 分支（35%）内接入 {@link #maybeStart}，随后每跳由 {@link com.holdhunderdyear.market.WorldTickService}
 * 调 {@link #tick} 推进，价格影响一律经 {@link com.holdhunderdyear.market.engine.ShockQueue}
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

    private final NotifyPublisher notifyPublisher;
    private final MessageService messageService;
    private final GameEventMapper gameEventMapper;

    /** 每用户当前剧本状态（key=userId）。 */
    private final Map<Long, MainScriptState> scripts = new ConcurrentHashMap<>();

    public MainScriptService(MessageService messageService, NotifyPublisher notifyPublisher,
                             GameEventMapper gameEventMapper) {
        this.messageService = messageService;
        this.notifyPublisher = notifyPublisher;
        this.gameEventMapper = gameEventMapper;
    }

    /** 剧本类型（权重：洗盘 0.45 / 出货 0.40 / 天地板 0.15）。 */
    enum ScriptKind { SHAKEOUT, PUMP_DUMP, SKY_EARTH }

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
        return startFor(userId, gs, board, rng, kind, stockId);
    }

    /** 强制启动指定类型与目标股的剧本（测试探针：自然启动耦合单据置困难时可控触发）。 */
    boolean startFor(long userId, UserGameState gs, QuoteBoard board, Random rng,
                     ScriptKind kind, long stockId) {
        MainScriptState st = buildState(kind, stockId, board, rng);
        scripts.put(userId, st);
        writeOpening(userId, gs, st);
        if (kind == ScriptKind.SKY_EARTH) {
            board.getShocks().enqueue(new PriceShock(stockId, false, -0.99, 1, PriceShock.Decay.ONCE));
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
            st.done = true; // 目标停牌价格冻结不再演变，终止剧本（防兜底死循环）
            return;
        }
        st.stageTicksLeft--;
        if (st.stageTicksLeft > 0) {
            return;
        }
        switch (st.stage) {
            case P1 -> { st.stage = Stage.P2; st.stageTicksLeft = st.n2; }
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
                    st.done = true;
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

    /** 剧本收官：置 done 并写一条完成消息。 */
    private void finish(long userId, UserGameState gs, MainScriptState st) {
        st.done = true;
        String name = StockDefs.byId(st.stockId).name();
        String title;
        String content;
        switch (st.type) {
            case SHAKEOUT -> {
                title = "洗盘完成";
                content = name + "洗盘完成，主力拉抬创出新高。";
            }
            case PUMP_DUMP -> {
                title = "拉高出货";
                content = name + "拉高出货完成，股价已破位下挫。";
            }
            default -> {
                title = "天地板";
                content = name + "单日天地板，从涨停直落跌停。";
            }
        }
        notifyPublisher.system(userId, gs.getCurrentDay(), title, content, st.stockId);
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
            default -> {
                return new MainScriptState(kind, stockId, Stage.P1, 1, 1, 1,
                        -0.99, 0.0, 0.0, 0.0, false, false);
            }
        }
    }

    /** 剧本开启落库：game_event（scriptType=类型）+ 用户消息（MEDIA/EVENT，标题含"主力"字样）。 */
    private void writeOpening(long userId, UserGameState gs, MainScriptState st) {
        String name = StockDefs.byId(st.stockId).name();
        String title;
        String content;
        String eventType;
        double impactPct;
        switch (st.type) {
            case SHAKEOUT -> {
                title = "主力洗盘开始";
                content = name + "出现恐慌性抛售，疑似主力洗盘吸筹。";
                eventType = "BAD";
                impactPct = Math.abs(st.mag1);
            }
            case PUMP_DUMP -> {
                title = "主力拉抬炒作";
                content = name + "放量拉升，主力资金正拉高出货。";
                eventType = "GOOD";
                impactPct = Math.abs(st.mag1);
            }
            default -> {
                title = "天地板来袭";
                content = name + "疑似主力对倒，天地板一触即发。";
                eventType = "BAD";
                impactPct = 0.99;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        GameEvent event = new GameEvent();
        event.setUserId(userId);
        event.setStockId(st.stockId);
        event.setScope("STOCK");
        event.setScriptType(st.type.name());
        event.setEventType(eventType);
        event.setImpactPattern("NONE");
        event.setTitle(title);
        event.setContent(content);
        event.setImpactPct(impactPct);
        event.setGameDay(gs.getCurrentDay());
        event.setCreatedAt(now);
        gameEventMapper.insert(event);

        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource("MEDIA");
        message.setType("EVENT");
        message.setTitle(title);
        message.setContent(content);
        message.setStockId(st.stockId);
        message.setIsRead(false);
        message.setGameDay(gs.getCurrentDay());
        message.setCreatedAt(now);
        messageService.publish(message);
    }

    /** 权重轮盘：SHAKEOUT 0.45 / PUMP_DUMP 0.40 / SKY_EARTH 0.15。 */
    private static ScriptKind rollKind(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.45) {
            return ScriptKind.SHAKEOUT;
        }
        if (r < 0.85) {
            return ScriptKind.PUMP_DUMP;
        }
        return ScriptKind.SKY_EARTH;
    }
}