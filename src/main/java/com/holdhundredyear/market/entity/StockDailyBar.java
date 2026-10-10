package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 个股日K线（每股每交易日一行，收盘结算时在重置 OHL 之前落库）：
 * open/high/low/close 为当日 OHLC，changePct 为当日涨跌幅（比率口径，0.04 = +4%）。
 * 停牌股同样落一行：价格冻结（open/high/low/close = 冻结价）、changePct = 0。
 */
@Data
@TableName("stock_daily_bar")
public class StockDailyBar {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long stockId;

    private Integer gameDay;

    private BigDecimal open;

    private BigDecimal high;

    private BigDecimal low;

    private BigDecimal close;

    private Double changePct;
}
