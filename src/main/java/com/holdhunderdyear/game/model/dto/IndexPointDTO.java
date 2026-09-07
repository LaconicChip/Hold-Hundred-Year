package com.holdhunderdyear.game.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 百年指数分时点（spec §11 GET /api/market/index 行结构）：
 * time 为 "HH:mm" 盘中时刻标签，value 为该跳指数点位。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexPointDTO {

    /** 盘中时刻标签（上午 09:30-11:30、下午 13:00-15:00 压缩映射）。 */
    private String time;

    private double value;
}
