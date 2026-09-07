package com.holdhunderdyear.game.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单股分时点（spec §11 GET /api/stocks/{id}/quotes 行结构）：
 * time 为 "HH:mm" 盘中时刻标签，price 为该跳股价。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntradayPointDTO {

    /** 盘中时刻标签（上午 09:30-11:30、下午 13:00-15:00 压缩映射）。 */
    private String time;

    private double price;
}
