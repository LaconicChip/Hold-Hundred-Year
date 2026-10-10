package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.LoanDTO;
import com.holdhundredyear.game.model.dto.LoanResultDTO;
import com.holdhundredyear.game.model.dto.SharkLoanDTO;
import com.holdhundredyear.trade.MarginService;
import com.holdhundredyear.trade.SharkService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final SharkService sharkService;

    public LoanController(MarginService marginService, SharkService sharkService) {
        this.marginService = marginService;
        this.sharkService = sharkService;
    }

    @PostMapping("/borrow")
    public R<LoanResultDTO> borrow(@AuthenticationPrincipal Long userId, @Valid @RequestBody LoanDTO dto) {
        return R.ok(marginService.borrow(userId, dto.getAmount()));
    }

    @PostMapping("/repay")
    public R<LoanResultDTO> repay(@AuthenticationPrincipal Long userId, @Valid @RequestBody LoanDTO dto) {
        return R.ok(marginService.repay(userId, dto.getAmount()));
    }

    /** 高利贷板块视图：余额、今日利息、到期倒计时、可借额度、逐笔台账。 */
    @GetMapping("/shark")
    public R<SharkLoanDTO> shark(@AuthenticationPrincipal Long userId) {
        return R.ok(sharkService.view(userId));
    }

    /** 借高利贷：额度上限是净资产 × SHARK_LIMIT_RATIO，超出直接拒。 */
    @PostMapping("/shark/borrow")
    public R<SharkLoanDTO> sharkBorrow(@AuthenticationPrincipal Long userId, @Valid @RequestBody LoanDTO dto) {
        return R.ok(sharkService.borrow(userId, dto.getAmount()));
    }

    /** 提前还高利贷：按到期日近的优先冲销。 */
    @PostMapping("/shark/repay")
    public R<SharkLoanDTO> sharkRepay(@AuthenticationPrincipal Long userId, @Valid @RequestBody LoanDTO dto) {
        return R.ok(sharkService.repay(userId, dto.getAmount()));
    }
}
