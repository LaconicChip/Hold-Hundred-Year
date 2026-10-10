package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.IntradayPointDTO;
import com.holdhundredyear.game.model.dto.KlinePointDTO;
import com.holdhundredyear.game.model.dto.StockQuoteDTO;
import com.holdhundredyear.market.MarketQueryService;
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

    public StockController(MarketQueryService marketQueryService) {
        this.marketQueryService = marketQueryService;
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
}
