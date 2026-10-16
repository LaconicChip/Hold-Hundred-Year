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
 * ① 指数大跌（≤ -2%）/ ② 指数大涨（≥ +2%）；③ 封板（任一 sealed=UP→涨停 / DOWN→跌停）；
 * ④ 暴涨暴跌（任一 |dayChangePct| &gt; 5%→急拉/跳水）；⑤ 小级别急动（3%~5%→次级急拉/跳水）；
 * ⑥ 横盘冷门（全部活跃股 |dayChangePct| &lt; 1%）；⑦ 否则 null。
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

    /** 同一类别两条之间的最少跳数：涨停封板能连钉 30 跳，不设冷却就是一屏"封死涨停"。 */
    static final int SAME_CATEGORY_COOLDOWN_TICKS = 10;
    /** 任意两条之间的最少跳数：弹幕是背景氛围，不是机关枪。 */
    static final int MIN_GAP_TICKS = 3;
    /** 记住最近几条文案，重复的直接不推（同一句看两次，人机味就出来了）。 */
    static final int RECENT_TEXT_MEMORY = 8;

    private final ObjectProvider<PushService> pushProvider;
    /** per-user 发送节流（内存态，跨日靠跳序回零自动复位；重启后从头计也没坏处）。 */
    private final Map<Long, Throttle> throttles = new ConcurrentHashMap<>();

    public DanmuService(ObjectProvider<PushService> pushProvider) {
        this.pushProvider = pushProvider;
    }

    /** 一条弹幕：类别（冷却与前端分组）、文案、焦点股（0 = 大盘级，不点名某只票）。 */
    record DanmuLine(String category, String text, long stockId) {
    }

    /** 纯判定函数：按 board 快照返回命中类别的一条文案，未命中返回 null。
     *  userId 为签名兼容保留（触发判定只读 board）。 */
    static String pickDanmu(long userId, QuoteBoard board, Random rng) {
        DanmuLine line = pickLine(userId, board, rng);
        return line == null ? null : line.text();
    }

    /** 纯判定函数：同上，但带上类别与焦点股。 */
    static DanmuLine pickLine(long userId, QuoteBoard board, Random rng) {
        // ①② 大盘级：只抽全局池
        if (board.getPrevIndex() > 0) {
            double indexPct = board.getIndexValue() / board.getPrevIndex() - 1;
            if (indexPct <= -0.02) {
                return new DanmuLine("大盘", pick(DanmuTexts.INDEX_DROP, rng), 0L);
            }
            if (indexPct >= 0.02) {
                return new DanmuLine("大盘", pick(DanmuTexts.INDEX_RISE, rng), 0L);
            }
        }
        // ③ 封板：焦点股就是封板那只
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            if (q.getSealed() == SealState.UP) {
                return new DanmuLine("涨停", pickStockFlavor(q, DanmuTexts.LIMIT_UP, rng), q.getStockId());
            }
            if (q.getSealed() == SealState.DOWN) {
                return new DanmuLine("跌停", pickStockFlavor(q, DanmuTexts.LIMIT_DOWN, rng), q.getStockId());
            }
        }
        boolean allCalm = true;
        Quote bigUp = null;
        Quote bigDown = null;
        Quote minorUp = null;
        Quote minorDown = null;
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            double pct = q.dayChangePct();
            double abs = Math.abs(pct);
            if (abs > 0.05) {
                if (pct > 0) {
                    bigUp = firstOrNull(bigUp, q);
                } else {
                    bigDown = firstOrNull(bigDown, q);
                }
            } else if (abs > 0.03) {
                if (pct > 0) {
                    minorUp = firstOrNull(minorUp, q);
                } else {
                    minorDown = firstOrNull(minorDown, q);
                }
            } else if (abs >= 0.01) {
                allCalm = false;
            }
        }
        // ④ 暴涨暴跌、⑤ 小级别急动：焦点股取第一个越线的股
        if (bigUp != null) {
            return new DanmuLine("急拉", pickStockFlavor(bigUp, DanmuTexts.RALLY, rng), bigUp.getStockId());
        }
        if (bigDown != null) {
            return new DanmuLine("跳水", pickStockFlavor(bigDown, DanmuTexts.DIVE, rng), bigDown.getStockId());
        }
        if (minorUp != null) {
            return new DanmuLine("急拉", pickStockFlavor(minorUp, DanmuTexts.RALLY_MINOR, rng), minorUp.getStockId());
        }
        if (minorDown != null) {
            return new DanmuLine("跳水", pickStockFlavor(minorDown, DanmuTexts.DIVE_MINOR, rng), minorDown.getStockId());
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
        DanmuLine line = pickLine(userId, board, WorldRandom.of(userId, "danmu" + tick));
        if (line == null || !allow(userId, tick, line)) {
            return;
        }
        push.pushDanmu(userId, payload(line));
    }

    /**
     * 冷却判定与记账（同一用户对象上同步，跨用户无竞争）。
     * 包内可见是为了让节流规则能被直接单测——它关系到"刷屏"这个玩家投诉点，
     * 不能只靠"看起来少了一点"来验收。
     */
    boolean allow(long userId, int tick, DanmuLine line) {
        Throttle t = throttles.computeIfAbsent(userId, key -> new Throttle());
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

    /** 下发载荷：带上类别与焦点股，个股页的股吧才分得出"在说谁"。 */
    static String payload(DanmuLine line) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", line.category());
        body.put("stockId", line.stockId());
        body.put("text", line.text());
        return MAPPER.writeValueAsString(body);
    }

    /** 单个用户的弹幕节流状态。 */
    private static final class Throttle {
        private final Map<String, Integer> byCategory = new HashMap<>();
        private final Deque<String> recent = new ArrayDeque<>();
        private int lastTick = -MIN_GAP_TICKS;
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

    /** 保留第一个命中的焦点股（后续同类越线股不覆盖）。 */
    private static Quote firstOrNull(Quote current, Quote candidate) {
        return current != null ? current : candidate;
    }

    /** 从类别池随机抽一条文案（非空池，nextIndex 恒在界内）。 */
    private static String pick(List<String> pool, Random rng) {
        return pool.get(rng.nextInt(pool.size()));
    }
}