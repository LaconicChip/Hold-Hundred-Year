package com.holdhundredyear.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_script_day")
public class UserScriptDay {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer gameDay;

    private String scriptType;

    private String paramsJson;
}
