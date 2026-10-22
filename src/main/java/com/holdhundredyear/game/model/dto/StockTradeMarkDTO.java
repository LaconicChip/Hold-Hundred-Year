package com.holdhundredyear.game.model.dto;

import lombok.Data;

/**
 * 个股图上的一个 B/S 成交标记：gameDay + tickIndex 定位，price 为成交价。
 * side 用大写 BUY/SELL，前端映射成 B/S。
 */
@Data
public class StockTradeMarkDTO {

    private long id;

    private int gameDay;

    private int tickIndex;

    private String side;

    private double price;

    private int shares;
}
