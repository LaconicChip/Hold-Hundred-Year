package com.holdhunderdyear.web;

import com.holdhunderdyear.common.R;
import com.holdhunderdyear.game.model.dto.IpoDTO;
import com.holdhunderdyear.market.entity.UserIpoRecord;
import com.holdhunderdyear.trade.IpoService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 打新接口：顶格申购新股。成功返回申购记录（幂等复用），业务拒绝经 BizException
 * 统一转为 400 + R.fail(ErrorCode)。
 */
@RestController
@RequestMapping("/api/ipo")
public class IpoController {

    private final IpoService ipoService;

    public IpoController(IpoService ipoService) {
        this.ipoService = ipoService;
    }

    @PostMapping("/subscribe")
    public R<UserIpoRecord> subscribe(@AuthenticationPrincipal Long userId,
                                      @Valid @RequestBody IpoDTO dto) {
        return R.ok(ipoService.subscribe(userId, dto.getScriptDayId()));
    }
}