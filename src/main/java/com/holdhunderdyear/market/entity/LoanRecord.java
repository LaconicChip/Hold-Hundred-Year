package com.holdhunderdyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("loan_record")
public class LoanRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String loanType;

    private BigDecimal amount;

    private BigDecimal debtAfter;

    private Integer gameDay;

    private LocalDateTime createdAt;
}
