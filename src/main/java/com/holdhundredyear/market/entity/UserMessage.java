package com.holdhundredyear.market.entity;

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

    /** 板块级消息的归属板块（与 StockDefs.sector 同集合）；单票与全市场消息为 null。 */
    private String sector;

    private Long followupOf;

    private Boolean isRead;

    private Integer gameDay;

    private LocalDateTime createdAt;
}
