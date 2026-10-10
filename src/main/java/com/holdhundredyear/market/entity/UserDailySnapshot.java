package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("user_daily_snapshot")
public class UserDailySnapshot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer gameDay;

    private BigDecimal cash;

    private BigDecimal marketValue;

    private BigDecimal debt;

    /** 高利贷未清余额（当日快照口径，net_asset 已扣掉它）。 */
    private BigDecimal sharkDebt;

    private BigDecimal netAsset;

    private Double dayReturnPct;

    private Double indexValue;

    private Double indexDayPct;

    private String npcAssetsJson;
}
