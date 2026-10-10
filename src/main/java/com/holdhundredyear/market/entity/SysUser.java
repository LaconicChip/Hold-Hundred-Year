package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String username;

    @ToString.Exclude
    private String password;

    private BigDecimal balance;

    private BigDecimal debt;

    /** 高利贷未清余额（与融资债分列：担保比例只读 debt，净资产读两者之和）。 */
    private BigDecimal sharkDebt;

    private LocalDateTime createdAt;
}
