package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("user_ipo_record")
public class UserIpoRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long scriptDayId;

    private String ipoName;

    private BigDecimal issuePrice;

    private String status;

    private BigDecimal profit;

    private Integer gameDay;

    private LocalDateTime createdAt;
}
