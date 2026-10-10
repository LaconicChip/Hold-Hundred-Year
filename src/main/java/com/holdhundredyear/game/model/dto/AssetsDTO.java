package com.holdhundredyear.game.model.dto;

import lombok.Data;

/**
 * 资产四项 + 担保比例（spec §11 GET /api/me/assets、§6.2 资产看板）。
 * guardRatio = (现金 + 持仓市值) ÷ 负债；无负债固定返回 0。
 */
@Data
public class AssetsDTO {

    private double cash;

    private double marketValue;

    private double debt;

    /** 高利贷未清余额：与融资负债分列，netAsset 里已经扣掉。 */
    private double sharkDebt;

    private double netAsset;

    private double guardRatio;

    /** 融资还能借多少（服务端算好，前端不再自己估）。 */
    private double borrowable;
}
