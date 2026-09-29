package com.holdhunderdyear.game.model.dto;

import lombok.Data;

/**
 * 单条融资流水（spec §11 GET /api/me/loan-records 行结构）。loanType 为后端原文
 * BORROW/REPAY/INTEREST/LIQUIDATE，amount 为本笔金额、debtAfter 为这笔操作后的负债余额
 * （均单位元），time 为 "yyyy-MM-dd HH:mm:ss"，gameDay 为局内交易日。
 */
@Data
public class LoanRecordItemDTO {

    private long id;

    private String time;

    private String loanType;

    private double amount;

    private double debtAfter;

    private int gameDay;
}
