package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("stock_info")
public class StockInfo {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String stockCode;

    private String stockName;

    private String sector;

    private BigDecimal basePrice;

    private Double beta;

    private Double indexWeight;
}
