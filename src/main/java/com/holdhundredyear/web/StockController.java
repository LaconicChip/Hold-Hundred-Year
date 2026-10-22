package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.event.DanmuService;
import com.holdhundredyear.event.DanmuService.DanmuPost;
import com.holdhundredyear.game.model.dto.IntradayPointDTO;
import com.holdhundredyear.game.model.dto.KlinePointDTO;
import com.holdhundredyear.game.model.dto.StockQuoteDTO;
import com.holdhundredyear.game.model.dto.StockTradeMarkDTO;
import com.holdhundredyear.market.MarketQueryService;
import com.holdhundredyear.trade.MeQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 股票行情接口（spec §11）：24 只股票静态信息 + 本账户最新行情、单股当日分时。
 * 只读查询委托 MarketQueryService（世界锁内快照读取）。
 */
@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final MarketQueryService marketQueryService;
    private final DanmuService danmuService;
    private final MeQueryService meQueryService;

    public StockController(MarketQueryService marketQueryService, DanmuService danmuService,
                           MeQueryService meQueryService) {
        this.marketQueryService = marketQueryService;
        this.danmuService = danmuService;
        this.meQueryService = meQueryService;
    }

    /** 24 只股票静态信息 + 本账户最新行情（StockDefs 档案序）。 */
    @GetMapping
    public R<List<StockQuoteDTO>> stocks(@AuthenticationPrincipal Long userId) {
        return R.ok(marketQueryService.stocksWithQuotes(userId));
    }

    /** 单股当日分时点序列（固定 90 槽时间轴，未到槽位为 null）。 */
    @GetMapping("/{id}/quotes")
    public R<List<IntradayPointDTO>> quotes(@AuthenticationPrincipal Long userId,
                                            @PathVariable("id") long stockId) {
        return R.ok(marketQueryService.stockIntraday(userId, stockId));
    }

    /** 单股日K序列：按 game_day 升序，limit 截取最近 N 根（默认 60）。 */
    @GetMapping("/{id}/kline")
    public R<List<KlinePointDTO>> kline(@AuthenticationPrincipal Long userId,
                                        @PathVariable("id") long stockId,
                                        @RequestParam(defaultValue = "60") int limit) {
        return R.ok(marketQueryService.stockKline(userId, stockId, limit));
    }

    /**
     * 单股股吧近期弹幕（2026-10-20 新增）：这只票专属 + 不点名的大盘闲聊，按 seq 升序。
     *
     * <p>为什么要有这个接口：弹幕只在盘中实时推，前端原本只留 30 条全局环形缓冲，
     * 24 只票一分，个股页打开时几乎总是空的（玩家反馈"股吧弹幕不会正常显示"）。
     * 后端按账户留一份每股历史，这里一次拉回来，页面才有"这里有人在聊"的样子。
     */
    @GetMapping("/{id}/danmu")
    public R<List<DanmuPost>> danmu(@AuthenticationPrincipal Long userId,
                                    @PathVariable("id") long stockId,
                                    @RequestParam(defaultValue = "80") int limit) {
        return R.ok(danmuService.recentFor(userId, stockId, limit));
    }

    /** 本账户在这只票上的全部成交标记：日K用 gameDay，分时用 tickIndex 定位 B/S。 */
    @GetMapping("/{id}/trades")
    public R<List<StockTradeMarkDTO>> trades(@AuthenticationPrincipal Long userId,
                                             @PathVariable("id") long stockId) {
        return R.ok(meQueryService.stockTrades(userId, stockId));
    }
}
