package com.holdhunderdyear.game.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 融资借款/还款请求体。字段级只做非空校验，业务规则（额度、现金）由
 * MarginService 校验链负责。
 */
@Data
public class LoanDTO {

    @NotNull(message = "金额不能为空")
    private BigDecimal amount;
}
