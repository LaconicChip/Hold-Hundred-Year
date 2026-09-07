package com.holdhunderdyear.game.model.dto;

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

    private double netAsset;

    private double guardRatio;
}
