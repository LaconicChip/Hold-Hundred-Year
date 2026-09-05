package com.holdhunderdyear.game.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 融资借款/还款回执：balance/debt 为操作后现金与负债，borrowable 为剩余
 * 可借额度 = 现金 + 持仓市值 − 负债 − 负债 × BORROW_LIMIT_RATIO（spec §6.4）。
 */
@Data
public class LoanResultDTO {

    /** 操作后账户余额 */
    private BigDecimal balance;

    /** 操作后负债 */
    private BigDecimal debt;

    /** 剩余可借额度 */
    private BigDecimal borrowable;
}
