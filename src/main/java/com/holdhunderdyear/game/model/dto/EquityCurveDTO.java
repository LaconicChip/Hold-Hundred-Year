package com.holdhunderdyear.game.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 资产 vs 指数曲线（Task 10.3，spec §11 GET /api/market/equity-curve）：
 * game_day 0..最新结算日的每日快照点，升序。
 */
@Data
public class EquityCurveDTO {

    /** 曲线点（gameDay 升序） */
    private List<Point> points;

    /** 曲线点：某交易日净资产与指数。 */
    @Data
    public static class Point {

        private int gameDay;

        private double netAsset;

        private double indexValue;
    }
}
