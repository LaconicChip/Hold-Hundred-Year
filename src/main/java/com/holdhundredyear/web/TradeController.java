package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.TradeDTO;
import com.holdhundredyear.game.model.dto.TradeResultDTO;
import com.holdhundredyear.trade.TradeService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易接口：买入/卖出。成功返回成交明细（price/shares/amount/commission/
 * tax/balance），业务拒绝经 BizException 统一转为 400 + R.fail(ErrorCode)。
 */
@RestController
@RequestMapping("/api/trade")
public class TradeController {

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @PostMapping("/buy")
    public R<TradeResultDTO> buy(@AuthenticationPrincipal Long userId, @Valid @RequestBody TradeDTO dto) {
        return R.ok(tradeService.buy(userId, dto));
    }

    @PostMapping("/sell")
    public R<TradeResultDTO> sell(@AuthenticationPrincipal Long userId, @Valid @RequestBody TradeDTO dto) {
        return R.ok(tradeService.sell(userId, dto));
    }
}
