package com.holdhunderdyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("user_game_state")
public class UserGameState {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer currentDay;

    private String marketState;

    private Integer tickIndex;

    private Long worldSeed;

    private Double indexValue;

    /** 当日开盘基准指数（昨收口径）：熔断阈值与盘中重启的唯一基准，见迁移 V20260929_01。 */
    private Double prevIndex;

    private BigDecimal prevTotalAsset;

    private String volRegime;

    private String tier;

    private String endReason;

    private Integer endDay;

    private LocalDateTime updatedAt;
}
