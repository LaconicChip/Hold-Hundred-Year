package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.GameModeDTO;
import com.holdhundredyear.game.model.dto.GameResultDTO;
import com.holdhundredyear.game.model.dto.RestartDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.GameEndService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 终局接口：查询双结局结果（只读）/ 重开新世界（清游戏数据、换 seed 重新播种，
 * 保留账号与密码）/ 列出可选的一局模式。业务拒绝经 BizException 统一转为 400 + R.fail(ErrorCode)。
 */
@RestController
@RequestMapping("/api/game")
public class GameController {

    private final GameEndService gameEndService;

    public GameController(GameEndService gameEndService) {
        this.gameEndService = gameEndService;
    }

    /** 可选的一局模式（开新局的选择器用）：档案只有一个来源 GameMode。 */
    @GetMapping("/modes")
    public R<List<GameModeDTO>> modes() {
        return R.ok(Arrays.stream(GameMode.values()).map(GameModeDTO::of).toList());
    }

    @GetMapping("/result")
    public R<GameResultDTO> result(@AuthenticationPrincipal Long userId) {
        return R.ok(gameEndService.result(userId));
    }

    /**
     * 开新的一局：body 可带 mode（STANDARD/QUICK），省略或留空则沿用本局模式。
     *
     * <p>留空必须走"沿用"而不是 {@link GameMode#of} 的未知兜底：终局页的"再来一局"不再
     * 提供模式选择器（模式在下一局第 1 天的状态栏选），它发的就是空 mode；若在这里兜成
     * 标准模式，快速模式的玩家重开一次就被悄悄换了档。
     */
    @PostMapping("/restart")
    public R<Void> restart(@AuthenticationPrincipal Long userId,
                           @RequestBody(required = false) RestartDTO dto) {
        String raw = dto == null ? null : dto.getMode();
        GameMode mode = (raw == null || raw.isBlank()) ? null : GameMode.of(raw);
        gameEndService.restart(userId, mode);
        return R.ok();
    }
}
