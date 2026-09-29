package com.holdhunderdyear.event;

import com.holdhunderdyear.game.constants.DanmuTexts;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.constants.StockPersonas;
import com.holdhunderdyear.game.model.enums.SealState;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.ws.PushService;
import com.holdhunderdyear.world.WorldRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

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

    private final ObjectProvider<PushService> pushProvider;

    public DanmuService(ObjectProvider<PushService> pushProvider) {
        this.pushProvider = pushProvider;
    }

    /** 纯判定函数：按 board 快照返回命中类别的一条文案，未命中返回 null。
     *  userId 为签名兼容保留（触发判定只读 board）。 */
    static String pickDanmu(long userId, QuoteBoard board, Random rng) {
        // ①② 大盘级：只抽全局池
        if (board.getPrevIndex() > 0) {
            double indexPct = board.getIndexValue() / board.getPrevIndex() - 1;
            if (indexPct <= -0.02) {
                return pick(DanmuTexts.INDEX_DROP, rng);
            }
            if (indexPct >= 0.02) {
                return pick(DanmuTexts.INDEX_RISE, rng);
            }
        }
        // ③ 封板：焦点股就是封板那只
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            if (q.getSealed() == SealState.UP) {
                return pickStockFlavor(q, DanmuTexts.LIMIT_UP, rng);
            }
            if (q.getSealed() == SealState.DOWN) {
                return pickStockFlavor(q, DanmuTexts.LIMIT_DOWN, rng);
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
            return pickStockFlavor(bigUp, DanmuTexts.RALLY, rng);
        }
        if (bigDown != null) {
            return pickStockFlavor(bigDown, DanmuTexts.DIVE, rng);
        }
        if (minorUp != null) {
            return pickStockFlavor(minorUp, DanmuTexts.RALLY_MINOR, rng);
        }
        if (minorDown != null) {
            return pickStockFlavor(minorDown, DanmuTexts.DIVE_MINOR, rng);
        }
        // ⑤ 横盘冷门
        if (allCalm) {
            return pick(DanmuTexts.FLAT, rng);
        }
        return null;
    }

    /** 轻量封装：命中则经软依赖 PushService 投递；未命中或推送不可用均静默。 */
    public void maybePush(long userId, QuoteBoard board) {
        Random rng = WorldRandom.of(userId, "danmu" + board.getTickIndex());
        String danmu = pickDanmu(userId, board, rng);
        if (danmu == null) {
            return;
        }
        PushService push = pushProvider.getIfAvailable();
        if (push == null) {
            return;
        }
        push.pushDanmu(userId, danmu);
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