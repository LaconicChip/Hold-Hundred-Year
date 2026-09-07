package com.holdhunderdyear.web;

import com.holdhunderdyear.common.R;
import com.holdhunderdyear.game.model.dto.IntradayPointDTO;
import com.holdhunderdyear.game.model.dto.StockQuoteDTO;
import com.holdhunderdyear.market.MarketQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /** 单股当日分时点序列（重启后从快照价重新积累，spec §5 已授权）。 */
    @GetMapping("/{id}/quotes")
    public R<List<IntradayPointDTO>> quotes(@AuthenticationPrincipal Long userId,
                                            @PathVariable("id") long stockId) {
        return R.ok(marketQueryService.stockIntraday(userId, stockId));
    }
}
