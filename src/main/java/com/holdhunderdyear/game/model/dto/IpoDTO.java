package com.holdhunderdyear.game.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 打新申购请求体：scriptDayId 即 user_script_day 中 scriptType=IPO 行的 id。
 * 字段级只做非空校验，业务规则（本用户/申购日/幂等）由 IpoService 负责。
 */
@Data
public class IpoDTO {

    @NotNull(message = "申购标的不为空")
    private Long scriptDayId;
}