package com.holdhunderdyear.game.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个股日K点（spec GET /api/stocks/{id}/kline 行结构）：gameDay 升序排列，
 * open/high/low/close 为当日 OHLC，changePct 为当日涨跌幅（比率，0.04 = +4%）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KlinePointDTO {

    private int gameDay;

    private double open;

    private double high;

    private double low;

    private double close;

    private double changePct;
}
