package com.holdhundredyear.market.entity;

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

    /** 成交时对应的分时槽位（0..89），供分时图精确画 B/S；旧数据默认 0。 */
    private Integer tickIndex;

    private LocalDateTime createdAt;
}
