package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.LoanDTO;
import com.holdhundredyear.game.model.dto.LoanResultDTO;
import com.holdhundredyear.trade.MarginService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 融资接口：借款/还款。成功返回操作后余额/负债/剩余可借额度，业务拒绝经
 * BizException 统一转为 400 + R.fail(ErrorCode)。
 */
@RestController
@RequestMapping("/api/loan")
public class LoanController {

    private final MarginService marginService;

    public LoanController(MarginService marginService) {
        this.marginService = marginService;
    }

    @PostMapping("/borrow")
    public R<LoanResultDTO> borrow(@AuthenticationPrincipal Long userId, @Valid @RequestBody LoanDTO dto) {
        return R.ok(marginService.borrow(userId, dto.getAmount()));
    }

    @PostMapping("/repay")
    public R<LoanResultDTO> repay(@AuthenticationPrincipal Long userId, @Valid @RequestBody LoanDTO dto) {
        return R.ok(marginService.repay(userId, dto.getAmount()));
    }
}
