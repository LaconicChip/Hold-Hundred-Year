package com.holdhunderdyear.game.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 成交明细（买入/卖出共用回执）。buy/sell 接口响应体与 Phase 10.2 的
 * trade-result WebSocket 推送共用此结构；tax 仅卖出非零，balance 为成交后余额。
 */
@Data
public class TradeResultDTO {

    /** 成交价（下单瞬间现价，2 位小数） */
    private BigDecimal price;

    /** 成交股数（100 的整数倍） */
    private int shares;

    /** 成交金额 = price × shares */
    private BigDecimal amount;

    /** 佣金（万 3，单笔最低 5 元，双边收取） */
    private BigDecimal commission;

    /** 印花税（千 1，仅卖出；买入恒为 0.00） */
    private BigDecimal tax;

    /** 成交后账户余额 */
    private BigDecimal balance;
}
