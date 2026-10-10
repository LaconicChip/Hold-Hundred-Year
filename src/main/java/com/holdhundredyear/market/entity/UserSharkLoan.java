package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 高利贷台账（spec §6.6）：一笔一行，各自带起算日与到期日。
 * 日息不落库——按账龄从 {@code GameConstants} 的递增档位推导，免得改了规则旧行还对不上。
 */
@Data
@TableName("user_shark_loan")
public class UserSharkLoan {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 到手本金（计息基数会随利滚利涨到它之上）。 */
    private BigDecimal principal;

    /** 当前未清余额（含已滚入的利息）。 */
    private BigDecimal balance;

    /** 起算日（借款成交的那个交易日）。 */
    private Integer startDay;

    /** 到期日：收盘仍未清就强制卖出持仓还债。 */
    private Integer dueDay;

    /** OPEN 在借 / OVERDUE 逾期未清 / REPAID 已结清。 */
    private String status;

    private LocalDateTime createdAt;
}
