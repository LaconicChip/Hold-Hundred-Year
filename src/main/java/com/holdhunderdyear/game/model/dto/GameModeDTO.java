package com.holdhunderdyear.game.model.dto;

import com.holdhunderdyear.game.model.enums.GameMode;
import lombok.Data;

/**
 * 一局可选模式（GET /api/game/modes）：给前端"开新局选模式"的选择器用。
 * 文案（名称与一句话说明）只有一份，来自 {@link GameMode}，前端不再另抄一遍。
 */
@Data
public class GameModeDTO {

    /** 模式代码（STANDARD/QUICK），重开时回传给 POST /api/game/restart。 */
    private String code;

    /** 模式名（选择器按钮标题）。 */
    private String label;

    /** 一句话说明（天数、大约耗时、涨跌停幅度）。 */
    private String description;

    /** 本局交易日总数。 */
    private int totalDays;

    /** 由档案生成。 */
    public static GameModeDTO of(GameMode mode) {
        GameModeDTO dto = new GameModeDTO();
        dto.setCode(mode.name());
        dto.setLabel(mode.getLabel());
        dto.setDescription(mode.getDescription());
        dto.setTotalDays(mode.totalDays());
        return dto;
    }
}
