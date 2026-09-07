package com.holdhunderdyear.game.model.dto;

import lombok.Data;

/**
 * 单只股票的静态档案 + 本账户最新行情（spec §11 GET /api/stocks 行结构）。
 * changePct 为小数比率（0.04 = +4%，与 WS 行情快照同约定）。
 */
@Data
public class StockQuoteDTO {

    private long stockId;

    private String code;

    private String name;

    private String sector;

    /** 发行价（StockDefs 档案）。 */
    private double basePrice;

    private double prevClose;

    private double open;

    private double current;

    private double high;

    private double low;

    /** 当日涨跌幅比率（0.04 = +4%）。 */
    private double changePct;

    private int limitUpStreak;

    private boolean halted;

    private boolean st;

    /** 封板状态：NONE/UP/DOWN。 */
    private String sealed;
}
