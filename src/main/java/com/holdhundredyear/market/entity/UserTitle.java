package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_title")
public class UserTitle {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String titleCode;

    private String titleName;

    private String kind;

    private Integer gameDay;

    private LocalDateTime createdAt;
}
