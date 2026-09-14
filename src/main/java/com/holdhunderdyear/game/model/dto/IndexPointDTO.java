package com.holdhunderdyear.game.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 百年指数分时点（spec §11 GET /api/market/index 行结构）：
 * time 为 "HH:mm" 盘中时刻标签，value 为该槽位指数点位——固定 90 槽时间轴下，
 * 未开盘/未到的槽位 value 为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexPointDTO {

    /** 盘中时刻标签（上午 09:30-11:30、下午 13:00-15:00 各 45 槽）。 */
    private String time;

    private Double value;
}
