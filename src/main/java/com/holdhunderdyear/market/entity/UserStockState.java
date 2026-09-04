package com.holdhunderdyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("user_stock_state")
public class UserStockState {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long stockId;

    private BigDecimal prevClose;

    private BigDecimal openPrice;

    private BigDecimal highPrice;

    private BigDecimal lowPrice;

    private BigDecimal currentPrice;

    private Integer limitUpStreak;

    private Boolean halted;

    private Integer haltUntilDay;

    private Boolean isSt;

    private Double gapPct;

    @Version
    private Integer version = 0;
}
