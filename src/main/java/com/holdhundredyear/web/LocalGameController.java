package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.LocalSaveDTO;
import com.holdhundredyear.world.LocalPlayerService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 便携版入口（只在 portable 档注册）：状态探测 + 继续游戏 + 创建新游戏。
 *
 * <p>路径挂在 {@code /api/auth/local/**} 下是有意为之——安全配置里
 * {@code /api/auth/**} 本来就放行，不需要为单机模式再改那张鉴权表（那张表的规矩是
 * "除 /api/auth/** 外的 /api/** 一律要登录"，见 SecurityConfig）。
 *
 * <p>前端的探测顺序是"先当单机，再当多人"：开页先打一次 status，拿到 200 就渲染
 * 继续游戏 / 创建新游戏，拿到 404 才渲染登录注册。所以默认档（线上部署那套）行为不变。
 */
@RestController
@RequestMapping("/api/auth/local")
@Profile("portable")
public class LocalGameController {

    private final LocalPlayerService localPlayerService;

    public LocalGameController(LocalPlayerService localPlayerService) {
        this.localPlayerService = localPlayerService;
    }

    /** 这台机器上有没有存档、存档长什么样（GET /api/auth/local/status）。 */
    @GetMapping("/status")
    public R<LocalSaveDTO> status() {
        return R.ok(localPlayerService.status());
    }

    /** 继续游戏：签发本地令牌（POST /api/auth/local/resume）。 */
    @PostMapping("/resume")
    public R<Map<String, Object>> resume() {
        return R.ok(localPlayerService.resume());
    }

    /** 创建新游戏：首次即建本地玩家，已有存档则清空重播（POST /api/auth/local/new）。 */
    @PostMapping("/new")
    public R<Map<String, Object>> createNew() {
        return R.ok(localPlayerService.newGame());
    }
}
