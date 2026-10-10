package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("user_position")
public class UserPosition {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long stockId;

    private Integer shares;

    private Integer availableShares;

    private BigDecimal avgCost;
}
