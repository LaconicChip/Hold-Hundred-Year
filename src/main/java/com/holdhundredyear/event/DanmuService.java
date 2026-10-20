package com.holdhundredyear.event;

import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.content.DanmuTexts;
import com.holdhundredyear.game.content.StockPersonas;
import com.holdhundredyear.game.model.enums.SealState;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldRandom;
import com.holdhundredyear.ws.PushService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 弹幕服务（Phase 8.3，spec §7.3）：按行情板当前快照判定触发类别并随机抽一条文案。
 *
 * <p>触发优先序（自顶向下首个命中即返回，全不命中返回 null 避免每跳刷屏）：
 * ① 封板（任一 sealed=UP→涨停 / DOWN→跌停，焦点票在封板的那几只里随机取）；
 * ② 指数大跌（≤ -2%）/ ③ 指数大涨（≥ +2%）；
 * ④ 暴涨暴跌（任一 |dayChangePct| > 5%→急拉/跳水）；⑤ 小级别急动（3%~5%→次级急拉/跳水）；
 * ⑥ 横盘冷门（全部活跃股 |dayChangePct| < 1%）；⑦ 否则 null。
 *
 * <p>文案三级混抽（解决"来去就那几句"）：个股级触发先抽该股专属池（{@link StockPersonas}），
 * 50% 命中；否则 25% 抽所属板块池（{@link DanmuTexts#SECTOR_FLAVOR}），余下抽全局类别池。
 * 大盘级与横盘触发只用全局池——股吧里聊指数不会点名某只票。
 * 判定只与 board 快照有关、与 rng 无关，rng 仅决定命中类别内抽哪条文案（确定可测）。
 *
 * <p>软依赖投递：构造器只注入 {@code ObjectProvider<PushService>}，maybePush 用
 * {@code getIfAvailable()} 判空——PushService bean 缺失时静默跳过，Task 10.2 落地后自动生效。
 * 弹幕仅推送、绝不写 user_message 表。
 */
@Service
public class DanmuService {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 同一类别两条之间的最少跳数（按票计）：封板能连钉 30 跳，不设冷却就是一屏"封死涨停"。 */
    static final int SAME_CATEGORY_COOLDOWN_TICKS = 2;
    /** 同一只票的两条之间的最少跳数：弹幕是背景氛围，不是机关枪。 */
    static final int MIN_GAP_TICKS = 1;
    /** 记住最近几条文案，重复的直接不推（同一句看两次，人机味就出来了）。 */
    static final int RECENT_TEXT_MEMORY = 8;
    /** 每只票保留的历史弹幕条数（内存态，够个股页翻一屏）。 */
    static final int HISTORY_PER_STOCK = 80;
    /** 大盘闲聊保留条数：每只票的股吧都带着它们。 */
    static final int HISTORY_MARKET = 20;
    /** 每跳每只票"开口说话"的概率基准（冷门票）。 */
    static final double CHATTER_BASE_P = 0.38;
    /** 热门加成：涨跌幅越大说话的人越多（涨跌幅 8% 时拿满）。 */
    static final double CHATTER_HOT_GAIN = 0.45;
    /** 封板额外加成：封板是全场最热的话题，人人都在问能不能买。 */
    static final double CHATTER_SEAL_BONUS = 0.3;
    /** 每跳出一条"不点名的大盘闲聊"的概率（每个股吧都看得见）。 */
    static final double MARKET_LINE_P = 0.2;
    /**
     * 票在动的时候，仍然说日常闲聊的概率。
     *
     * <p>真实股吧里绝大多数帖子是闲话（这票明天怎么看、我是不是该割了），涨跌播报只是其中
     * 一小撮。以前涨跌一有动静就必说涨跌，股吧里清一色"急拉/跳水"，看着像行情播报不像人话。
     */
    static final double CHATTER_MIX_WHEN_MOVING = 0.35;

    private final ObjectProvider<PushService> pushProvider;
    /**
     * per-user × per-stock 发送节流（内存态，跨日靠跳序回零自动复位；重启后从头计也没坏处）。
     *
     * <p><b>按票计</b>是 2026-10-20 改的：原来节流是整个账户一把锁（任意两条隔 3 跳），
     * 于是全市场一天只能出三十来条、24 只票一分每票 1~2 条——玩家反馈"弹幕太少了"。
     * 真实的股吧是每只票各自有人说话，各有各的节奏，所以冷却也要按票算。
     */
    private final Map<Long, Map<Long, Throttle>> throttles = new ConcurrentHashMap<>();
    /** per-user 弹幕历史（内存态）：个股页要能翻出"这只票最近在聊什么"。 */
    private final Map<Long, UserDanmu> histories = new ConcurrentHashMap<>();

    public DanmuService(ObjectProvider<PushService> pushProvider) {
        this.pushProvider = pushProvider;
    }

    /** 一条弹幕：类别（冷却与前端分组）、文案、焦点股（0 = 大盘级，不点名某只票）。 */
    record DanmuLine(String category, String text, long stockId) {
    }

    /**
     * 一条已推送的弹幕（带本账户内自增序号与产生它的跳号）。
     *
     * <p>序号由后端统一发：前端拿它去重（WS 实时推的那条与历史接口拉回来的那条是同一条），
     * 也用它做"最新在上"的排序依据。跳号留着量节奏用——"弹幕是一波一波还是持续的"
     * 靠看相邻两条的跳号间隔才能说话。
     */
    public record DanmuPost(long seq, String category, String text, long stockId, int tick) {
    }

    /** 纯判定函数：按 board 快照返回命中类别的一条文案，未命中返回 null。
     *  userId 为签名兼容保留（触发判定只读 board）。 */
    static String pickDanmu(long userId, QuoteBoard board, Random rng) {
        DanmuLine line = pickLine(userId, board, rng);
        return line == null ? null : line.text();
    }

    /** 纯判定函数：同上，但带上类别与焦点股。 */
    static DanmuLine pickLine(long userId, QuoteBoard board, Random rng) {
        // ① 封板：焦点票在封板的那几只里随机取
        // （旧版永远取"第一只封板的票"，弹幕于是全落在同一只票上，别的票的股吧一直空着）
        // 优先级放在大盘之前（2026-10-20）：个股页的股吧该先说"这只票封板了"，
        // 而不是先说大盘涨跌——日 σ 抬到 5% 之后指数经常 ±2%，大盘级弹幕会把名额占满，
        // 结果每只票的股吧里全是"指数高歌猛进"这类不点名的闲聊。
        List<Quote> sealedUp = new ArrayList<>();
        List<Quote> sealedDown = new ArrayList<>();
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            if (q.getSealed() == SealState.UP) {
                sealedUp.add(q);
            } else if (q.getSealed() == SealState.DOWN) {
                sealedDown.add(q);
            }
        }
        if (!sealedUp.isEmpty()) {
            Quote focus = sealedUp.get(rng.nextInt(sealedUp.size()));
            return new DanmuLine("涨停", pickStockFlavor(focus, DanmuTexts.LIMIT_UP, rng), focus.getStockId());
        }
        if (!sealedDown.isEmpty()) {
            Quote focus = sealedDown.get(rng.nextInt(sealedDown.size()));
            return new DanmuLine("跌停", pickStockFlavor(focus, DanmuTexts.LIMIT_DOWN, rng), focus.getStockId());
        }
        // ② 大盘级：只抽全局池
        if (board.getPrevIndex() > 0) {
            double indexPct = board.getIndexValue() / board.getPrevIndex() - 1;
            if (indexPct <= -0.02) {
                return new DanmuLine("大盘", pick(DanmuTexts.INDEX_DROP, rng), 0L);
            }
            if (indexPct >= 0.02) {
                return new DanmuLine("大盘", pick(DanmuTexts.INDEX_RISE, rng), 0L);
            }
        }
        boolean allCalm = true;
        List<Quote> bigUps = new ArrayList<>();
        List<Quote> bigDowns = new ArrayList<>();
        List<Quote> minorUps = new ArrayList<>();
        List<Quote> minorDowns = new ArrayList<>();
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            double pct = q.dayChangePct();
            double abs = Math.abs(pct);
            if (abs > 0.05) {
                (pct > 0 ? bigUps : bigDowns).add(q);
            } else if (abs > 0.03) {
                (pct > 0 ? minorUps : minorDowns).add(q);
            } else if (abs >= 0.01) {
                allCalm = false;
            }
        }
        // ④ 暴涨暴跌、⑤ 小级别急动：焦点票在同类里随机取（见上：不能让弹幕总落在同一只票上）
        if (!bigUps.isEmpty()) {
            Quote focus = bigUps.get(rng.nextInt(bigUps.size()));
            return new DanmuLine("急拉", pickStockFlavor(focus, DanmuTexts.RALLY, rng), focus.getStockId());
        }
        if (!bigDowns.isEmpty()) {
            Quote focus = bigDowns.get(rng.nextInt(bigDowns.size()));
            return new DanmuLine("跳水", pickStockFlavor(focus, DanmuTexts.DIVE, rng), focus.getStockId());
        }
        if (!minorUps.isEmpty()) {
            Quote focus = minorUps.get(rng.nextInt(minorUps.size()));
            return new DanmuLine("急拉", pickStockFlavor(focus, DanmuTexts.RALLY_MINOR, rng), focus.getStockId());
        }
        if (!minorDowns.isEmpty()) {
            Quote focus = minorDowns.get(rng.nextInt(minorDowns.size()));
            return new DanmuLine("跳水", pickStockFlavor(focus, DanmuTexts.DIVE_MINOR, rng), focus.getStockId());
        }
        // ⑤ 横盘冷门
        if (allCalm) {
            return new DanmuLine("清淡", pick(DanmuTexts.FLAT, rng), 0L);
        }
        return null;
    }

    /**
     * 命中且通过节流才投递；未命中、被冷却挡下、或推送不可用一律静默。
     *
     * <p>节流是"人机味"的主要解药：旧版每跳最多一条、且同一天同一句能连着来八遍。
     * 弹幕不写库、不影响行情，所以挡掉几条不改变任何可复现结果（掷骰仍按跳派生）。
     */
    public void maybePush(long userId, QuoteBoard board) {
        PushService push = pushProvider.getIfAvailable();
        if (push == null) {
            return;
        }
        int tick = board.getTickIndex();
        // ① 每只票各自的流：热门的票话多、冷门的票偶尔一句（真实的股吧就是这样）
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            long stockId = q.getStockId();
            // 每只票一条自己的随机流：24 只票共用一条流会让"谁在说话"绑在同一拍上，
            // 看上去是全场一起刷屏；分开之后各说各的，节奏才像真的。
            Random rng = WorldRandom.of(userId, "danmu" + tick + "." + stockId);
            if (!dueForSpeech(userId, stockId, tick, chatterProbability(q), rng)) {
                continue;
            }
            DanmuLine line = pickLineFor(q, rng);
            if (!allow(userId, tick, line)) {
                continue;
            }
            long seq = remember(userId, line, tick);
            push.pushDanmu(userId, payload(line, seq, tick));
        }
        // ② 一条不点名的大盘闲聊：每个股吧都看得见（指数涨跌、全场清淡这类）
        Random marketRng = WorldRandom.of(userId, "danmu" + tick + ".market");
        if (marketRng.nextDouble() < MARKET_LINE_P) {
            DanmuLine market = pickLine(userId, board, marketRng);
            if (market != null && allow(userId, tick, market)) {
                long seq = remember(userId, market, tick);
                push.pushDanmu(userId, payload(market, seq, tick));
            }
        }
    }

    /**
     * 这只票这一跳"有人说话"的概率：冷门基准 35%，涨得越多话越多，封板再加一档。
     *
     * <p>这就是"热点票的股吧永远比冷门票热闹"的量化版——玩家抱怨"每只票尤其热点票应该
     * 每时每刻都有好多条弹幕"，而冷门票也该有人问一句"这票怎么不动"。
     */
    static double chatterProbability(Quote q) {
        double heat = Math.min(1.0, Math.abs(q.dayChangePct()) / 0.08);
        double p = CHATTER_BASE_P + CHATTER_HOT_GAIN * heat;
        if (q.getSealed() != SealState.NONE) {
            p += CHATTER_SEAL_BONUS;
        }
        return Math.min(0.9, p);
    }

    /**
     * 这只票这一跳该轮到他说话了没——按"下一次发言时刻"排期，间隔服从指数分布。
     *
     * <p><b>为什么不用"每跳掷骰"</b>：那样出来的间隔是几何分布，会成团（连着两三条），
     * 看上去就是"一轮一轮的"；而且同类别冷却再叠一层，节奏会固定成"隔两跳一条"，
     * 更像机关枪不像人。这里改成给每只票排一个随机的"下次发言跳号"，
     * 间隔在 1~几跳之间随机，连着来与隔很久来都会出现——节奏才是活的。
     */
    private boolean dueForSpeech(long userId, long stockId, int tick, double p, Random rng) {
        Throttle t = throttleOf(userId, stockId);
        synchronized (t) {
            if (tick < t.lastTick) {
                // 跨日：跳序回零，一切重排
                t.nextSpeechTick = -1;
                t.byCategory.clear();
                t.lastTick = tick - MIN_GAP_TICKS;
            }
            if (t.nextSpeechTick < 0 || tick >= t.nextSpeechTick) {
                double mean = 1.0 / Math.max(0.05, p);
                // 间隔服从指数分布：1 跳、2 跳、5 跳、9 跳都会出现（不是固定节奏）
                int interval = (int) (-Math.log(1.0 - rng.nextDouble()) * mean);
                t.nextSpeechTick = tick + Math.max(1, interval);
                return true;
            }
            return false;
        }
    }

    /**
     * 一条<b>关于某只票</b>的弹幕：类别按这只票自己的状态选，文案走三级混抽。
     *
     * <p>涨跌之外一律落到"闲聊"——这才是股吧里绝大多数帖子（这票明天怎么看、主力在干嘛、
     * 我是不是该割了），否则每只票的股吧除了涨跌播报就没别的了。
     */
    static DanmuLine pickLineFor(Quote q, Random rng) {
        double chg = q.dayChangePct();
        double abs = Math.abs(chg);
        // 票在动的时候也常有闲话：先掷一次决定"说涨跌"还是"说闲话"（横盘的票不必掷，直接说闲话）
        boolean moving = q.getSealed() != SealState.NONE || abs > 0.03;
        boolean chatter = !moving || rng.nextDouble() < CHATTER_MIX_WHEN_MOVING;
        if (q.getSealed() == SealState.UP && !chatter) {
            return new DanmuLine("涨停", pickStockFlavor(q, DanmuTexts.LIMIT_UP, rng), q.getStockId());
        }
        if (q.getSealed() == SealState.DOWN && !chatter) {
            return new DanmuLine("跌停", pickStockFlavor(q, DanmuTexts.LIMIT_DOWN, rng), q.getStockId());
        }
        if (abs > 0.05 && !chatter) {
            return chg > 0
                    ? new DanmuLine("急拉", pickStockFlavor(q, DanmuTexts.RALLY, rng), q.getStockId())
                    : new DanmuLine("跳水", pickStockFlavor(q, DanmuTexts.DIVE, rng), q.getStockId());
        }
        if (abs > 0.03 && !chatter) {
            return chg > 0
                    ? new DanmuLine("急拉", pickStockFlavor(q, DanmuTexts.RALLY_MINOR, rng), q.getStockId())
                    : new DanmuLine("跳水", pickStockFlavor(q, DanmuTexts.DIVE_MINOR, rng), q.getStockId());
        }
        return new DanmuLine("闲聊", pickStockFlavor(q, DanmuTexts.CHATTER, rng), q.getStockId());
    }

    /**
     * 记进本账户的弹幕历史并分配序号（内存态：弹幕不落库，重启后从头计）。
     *
     * <p>历史是"个股页能翻出这只票最近在聊什么"的唯一来源——前端原来只有 30 条全局环形缓冲，
     * 24 只票一分，个股页几乎总是空的（玩家反馈"股吧弹幕不会正常显示"）。
     */
    private long remember(long userId, DanmuLine line, int tick) {
        UserDanmu history = histories.computeIfAbsent(userId, key -> new UserDanmu());
        synchronized (history) {
            long seq = ++history.seq;
            DanmuPost post = new DanmuPost(seq, line.category(), line.text(), line.stockId(), tick);
            if (line.stockId() > 0) {
                Deque<DanmuPost> queue = history.byStock.computeIfAbsent(line.stockId(), key -> new ArrayDeque<>());
                queue.addLast(post);
                while (queue.size() > HISTORY_PER_STOCK) {
                    queue.removeFirst();
                }
            } else {
                history.market.addLast(post);
                while (history.market.size() > HISTORY_MARKET) {
                    history.market.removeFirst();
                }
            }
            return seq;
        }
    }

    /**
     * 某只票的近期弹幕：这只票专属 + 不点名的大盘闲聊（按 seq 升序，最新在后）。
     *
     * <p>与前端 {@code forumPosts} 的过滤口径一致（"在说这只票的 + 不点名的大盘闲聊"）。
     */
    public List<DanmuPost> recentFor(long userId, long stockId, int limit) {
        UserDanmu history = histories.get(userId);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            List<DanmuPost> all = new ArrayList<>(history.byStock.getOrDefault(stockId, new ArrayDeque<>()));
            all.addAll(history.market);
            all.sort(Comparator.comparingLong(DanmuPost::seq));
            int from = Math.max(0, all.size() - Math.max(1, limit));
            return List.copyOf(all.subList(from, all.size()));
        }
    }

    /**
     * 冷却判定与记账（同一只票上同步，跨票互不影响）。
     * 包内可见是为了让节流规则能被直接单测——它关系到"刷屏"这个玩家投诉点，
     * 不能只靠"看起来少了一点"来验收。
     *
     * <p>冷却按<b>票</b>算（2026-10-20）：每只票有自己的股吧、各有各的刷屏节奏。
     * 早年是整个账户一把锁（任意两条隔 3 跳），结果全市场一天只出三十来条。
     */
    boolean allow(long userId, int tick, DanmuLine line) {
        Throttle t = throttleOf(userId, line.stockId());
        synchronized (t) {
            if (tick < t.lastTick) {
                // 跨日：跳序回零，冷却表整体作废，并把"上一条"推到本跳之前刚好满足最小间隔。
                // 别用 Integer.MIN_VALUE 当"很久以前"：tick - MIN_VALUE 会溢出成负数，
                // 结果第二天开盘第一跳反而永远被挡（这条是单测抓出来的）。
                t.byCategory.clear();
                t.lastTick = tick - MIN_GAP_TICKS;
            }
            Integer lastOfCategory = t.byCategory.get(line.category());
            if (lastOfCategory != null && tick - lastOfCategory < SAME_CATEGORY_COOLDOWN_TICKS) {
                return false;
            }
            if (tick - t.lastTick < MIN_GAP_TICKS) {
                return false;
            }
            if (t.recent.contains(line.text())) {
                return false;
            }
            t.byCategory.put(line.category(), tick);
            t.lastTick = tick;
            t.recent.addLast(line.text());
            while (t.recent.size() > RECENT_TEXT_MEMORY) {
                t.recent.removeFirst();
            }
            return true;
        }
    }

    /** 取（并初始化）某个用户在某只票上的节流/排期状态。 */
    private Throttle throttleOf(long userId, long stockId) {
        return throttles.computeIfAbsent(userId, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(stockId, key -> new Throttle());
    }

    /** 下发载荷：带上类别与焦点股，个股页的股吧才分得出"在说谁"。 */
    static String payload(DanmuLine line) {
        return payload(line, 0L, 0);
    }

    /** 下发载荷（带本账户内序号与跳号）：前端据此把实时推送与历史接口拉回来的条目去重合并。 */
    static String payload(DanmuLine line, long seq, int tick) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seq", seq);
        body.put("tick", tick);
        body.put("category", line.category());
        body.put("stockId", line.stockId());
        body.put("text", line.text());
        return MAPPER.writeValueAsString(body);
    }

    /** 单个用户的单只票的节流与排期状态。 */
    private static final class Throttle {
        private final Map<String, Integer> byCategory = new HashMap<>();
        private final Deque<String> recent = new ArrayDeque<>();
        private int lastTick = -MIN_GAP_TICKS;
        /** 下一次发言的跳号（-1 = 未排期）：按随机间隔排期，节奏才不会"一轮一轮"。 */
        private int nextSpeechTick = -1;
    }

    /** 单个用户的弹幕历史（内存态）：每股最近若干条 + 大盘闲聊若干条。 */
    private static final class UserDanmu {
        /** 本账户内自增序号（前端据此去重与排序）。 */
        private long seq;
        /** stockId → 该票的弹幕（焦点票专属）。 */
        private final Map<Long, Deque<DanmuPost>> byStock = new HashMap<>();
        /** 不点名的大盘闲聊：每只票的股吧都带着它们。 */
        private final Deque<DanmuPost> market = new ArrayDeque<>();
    }

    /**
     * 个股级触发的三级混抽：50% 该股专属 → 25% 所属板块 → 25% 全局类别池。
     * 无论是否命中都消费一个 double，保证同 seed 的抽文案序列与池子内容无关。
     */
    private static String pickStockFlavor(Quote focus, List<String> globalPool, Random rng) {
        double roll = rng.nextDouble();
        if (roll < 0.5 && StockPersonas.has(focus.getStockId())) {
            return pick(StockPersonas.byId(focus.getStockId()).danmu(), rng);
        }
        if (roll < 0.75) {
            return pick(DanmuTexts.sectorPool(StockDefs.byId(focus.getStockId()).sector()), rng);
        }
        return pick(globalPool, rng);
    }

    /** 从类别池随机抽一条文案（非空池，nextIndex 恒在界内）。 */
    private static String pick(List<String> pool, Random rng) {
        return pool.get(rng.nextInt(pool.size()));
    }
}