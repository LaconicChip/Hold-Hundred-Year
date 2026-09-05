package com.holdhunderdyear.game.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 委托请求体（买入/卖出共用）。字段级只做非空校验，业务规则（整手、
 * 涨跌停、资金/持仓）由 TradeService 校验链负责。
 */
@Data
public class TradeDTO {

    @NotNull(message = "股票不能为空")
    private Long stockId;

    @NotNull(message = "委托数量不能为空")
    private Integer shares;
}
