package com.holdhundredyear.market.entity;

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

    /** 当日涨跌幅（比率，0.04 = +4%）：盘中为 null，收盘结算写入当日收盘涨跌幅——
     *  结算后 prev_close 双写为收盘价，current==prevClose，实时涨跌幅恒为 0，
     *  收盘态展示与行情板恢复（QuoteBoard.restore）都走本列。 */
    private Double dayChangePct;

    @Version
    private Integer version = 0;
}
