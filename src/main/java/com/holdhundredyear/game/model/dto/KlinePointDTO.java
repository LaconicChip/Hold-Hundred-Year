package com.holdhundredyear.game.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个股日K点（spec GET /api/stocks/{id}/kline 行结构）：gameDay 升序排列，
 * open/high/low/close 为当日 OHLC 的**前复权价**（最新一根等于真实成交价，越往前越可能被
 * 除权除息压得更低，图上是连续曲线），changePct 为当日涨跌幅（比率，0.04 = +4%）——
 * 收益率不受复权影响，所以它始终等于玩家当天真实经历的涨跌。
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
