package com.holdhunderdyear.web;

import com.holdhunderdyear.common.R;
import com.holdhunderdyear.game.model.dto.GameResultDTO;
import com.holdhunderdyear.market.GameEndService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 终局接口：查询双结局结果（只读）/ 重开新世界（清游戏数据、换 seed 重新播种，
 * 保留账号与密码）。业务拒绝经 BizException 统一转为 400 + R.fail(ErrorCode)。
 */
@RestController
@RequestMapping("/api/game")
public class GameController {

    private final GameEndService gameEndService;

    public GameController(GameEndService gameEndService) {
        this.gameEndService = gameEndService;
    }

    @GetMapping("/result")
    public R<GameResultDTO> result(@AuthenticationPrincipal Long userId) {
        return R.ok(gameEndService.result(userId));
    }

    @PostMapping("/restart")
    public R<Void> restart(@AuthenticationPrincipal Long userId) {
        gameEndService.restart(userId);
        return R.ok();
    }
}
