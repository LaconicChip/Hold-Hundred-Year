package com.holdhundredyear.game.model.dto;

import lombok.Data;

/**
 * 开新一局的请求体（body 可为空）。mode 省略时沿用本局当前模式；传 STANDARD / QUICK
 * 则为新的一局选定模式（大小写不敏感，未知值按标准模式兜底）。
 */
@Data
public class RestartDTO {

    private String mode;
}
