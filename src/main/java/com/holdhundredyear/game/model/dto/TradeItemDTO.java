package com.holdhundredyear.game.model.dto;

import lombok.Data;

/**
 * 单条交易流水（spec §11 GET /api/me/trades 行结构）。side 为小写 "buy"/"sell"，
 * time 为成交时间 "yyyy-MM-dd HH:mm:ss"。
 */
@Data
public class TradeItemDTO {

    private long id;

    private String time;

    private long stockId;

    private String code;

    private String name;

    private String side;

    private int shares;

    private double price;

    private double amount;

    private double commission;

    private double tax;

    /** 局内交易日与分时槽位：资产页流水按日定位，图表 B/S 用。 */
    private int gameDay;

    private int tickIndex;
}
