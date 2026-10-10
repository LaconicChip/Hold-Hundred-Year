package com.holdhundredyear.game.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息中心单条消息。字段与 user_message 实体一一对应，原样返回给前端；
 * followup 追报作为独立消息返回，前端凭 followupOf 关联原消息，不做服务端拼装。
 */
@Data
public class MessageDTO {

    private Long id;

    private Long userId;

    /** 来源，EventSkeleton.Source 枚举名之一 */
    private String source;

    /** 真伪三态：PENDING/CONFIRMED/DENIED；无三态来源（FUN/NPC/SYSTEM）为 null */
    private String truthStatus;

    private String type;

    private String title;

    private String content;

    private Long stockId;

    /** 板块级消息的归属板块；单票/全市场消息为 null */
    private String sector;

    /** 追报原消息 id；普通消息为 null */
    private Long followupOf;

    private Boolean isRead;

    private Integer gameDay;

    private LocalDateTime createdAt;
}