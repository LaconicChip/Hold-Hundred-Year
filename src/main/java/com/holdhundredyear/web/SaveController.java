package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.RestartDTO;
import com.holdhundredyear.game.model.dto.SaveSummaryDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.world.SaveService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 存档架接口：列存档 / 新建 / 进入 / 删除。
 *
 * <p>这组接口<b>不需要登录</b>（安全配置里放行 {@code /api/saves/**}）：这一版已经删掉
 * 账号系统，存档架就是入口本身；局域网里的朋友打开同一个页面看到的是同一张架子。
 * 进入某一份存档时签发 JWT，之后所有游戏数据接口仍按该存档的 userId 隔离。
 */
@RestController
@RequestMapping("/api/saves")
public class SaveController {

    private final SaveService saveService;

    public SaveController(SaveService saveService) {
        this.saveService = saveService;
    }

    /** 全部存档（最近玩过的排最前）。 */
    @GetMapping
    public R<List<SaveSummaryDTO>> list() {
        return R.ok(saveService.list());
    }

    /** 新建一份存档：body 可选 mode（STANDARD/QUICK），缺省标准模式。 */
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody(required = false) RestartDTO dto) {
        String raw = dto == null ? null : dto.getMode();
        GameMode mode = (raw == null || raw.isBlank()) ? GameMode.STANDARD : GameMode.of(raw);
        return R.ok(saveService.create(mode));
    }

    /** 进入一份存档：签发令牌。 */
    @PostMapping("/{id}/open")
    public R<Map<String, Object>> open(@PathVariable long id) {
        return R.ok(saveService.open(id));
    }

    /** 删除一份存档（连同该存档的全部游戏数据，不可恢复）。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        saveService.delete(id);
        return R.ok();
    }
}
