package com.holdhundredyear.game.model.dto;

import lombok.Data;

/**
 * 单条持仓（spec §11 GET /api/me/positions 行结构）：持仓档案 + 现价市值 + 浮盈。
 * costPrice 即 avg_cost；current 来自本账户行情板现价。
 */
@Data
public class PositionDTO {

    private long stockId;

    private String code;

    private String name;

    private String sector;

    private int shares;

    /** 可卖股数（T+1 冻结，收盘解冻）。 */
    private int availableShares;

    private double costPrice;

    private double current;

    private double marketValue;

    /** 浮动盈亏 = (current − costPrice) × shares。 */
    private double profit;

    /** 盈亏率百分数（-1.23 表示 −1.23%）。 */
    private double profitPct;

    private boolean halted;

    private boolean st;
}
