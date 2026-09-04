package com.holdhunderdyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trade_record")
public class TradeRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long stockId;

    private String tradeType;

    private BigDecimal price;

    private Integer shares;

    private BigDecimal amount;

    private BigDecimal commission;

    private BigDecimal tax;

    private Integer gameDay;

    private LocalDateTime createdAt;
}
