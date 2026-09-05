package com.holdhunderdyear.event;

import com.holdhunderdyear.game.constants.DanmuTexts;
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
 * ① 指数大跌（日内跌幅 ≤ -2%）；② 封板（任一 sealed=UP→涨停封板 / DOWN→跌停封板）；
 * ③ 暴涨暴跌（任一 |dayChangePct| &gt; 5%→急拉/跳水）；④ 小级别急动（3%~5%→次级急拉/跳水）；
 * ⑤ 横盘冷门（全部活跃股 |dayChangePct| &lt; 1%）；⑥ 否则 null。
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
        // ① 指数大跌
        if (board.getPrevIndex() > 0 && board.getIndexValue() / board.getPrevIndex() - 1 <= -0.02) {
            return pick(DanmuTexts.INDEX_DROP, rng);
        }
        // ② 封板
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            if (q.getSealed() == SealState.UP) {
                return pick(DanmuTexts.LIMIT_UP, rng);
            }
            if (q.getSealed() == SealState.DOWN) {
                return pick(DanmuTexts.LIMIT_DOWN, rng);
            }
        }
        boolean allCalm = true;
        boolean bigUp = false;
        boolean bigDown = false;
        boolean minorUp = false;
        boolean minorDown = false;
        for (Quote q : board.getQuotes().values()) {
            if (q.isHalted()) {
                continue;
            }
            double pct = q.dayChangePct();
            double abs = Math.abs(pct);
            if (abs > 0.05) {
                if (pct > 0) {
                    bigUp = true;
                } else {
                    bigDown = true;
                }
            } else if (abs > 0.03) {
                if (pct > 0) {
                    minorUp = true;
                } else {
                    minorDown = true;
                }
            } else if (abs >= 0.01) {
                allCalm = false;
            }
        }
        // ③ 暴涨暴跌
        if (bigUp) {
            return pick(DanmuTexts.RALLY, rng);
        }
        if (bigDown) {
            return pick(DanmuTexts.DIVE, rng);
        }
        // ④ 小级别急动
        if (minorUp) {
            return pick(DanmuTexts.RALLY_MINOR, rng);
        }
        if (minorDown) {
            return pick(DanmuTexts.DIVE_MINOR, rng);
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

    /** 从类别池随机抽一条文案（非空池，nextIndex 恒在界内）。 */
    private static String pick(List<String> pool, Random rng) {
        return pool.get(rng.nextInt(pool.size()));
    }
}