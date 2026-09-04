package com.holdhunderdyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_message")
public class UserMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String source;

    private String truthStatus;

    private String type;

    private String title;

    private String content;

    private Long stockId;

    private Long followupOf;

    private Boolean isRead;

    private Integer gameDay;

    private LocalDateTime createdAt;
}
