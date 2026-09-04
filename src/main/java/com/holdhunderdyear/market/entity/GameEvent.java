package com.holdhunderdyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("game_event")
public class GameEvent {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long stockId;

    private String scope;

    private String scriptType;

    private String eventType;

    private String impactPattern;

    private String title;

    private String content;

    private Double impactPct;

    private Integer gameDay;

    private LocalDateTime createdAt;
}
