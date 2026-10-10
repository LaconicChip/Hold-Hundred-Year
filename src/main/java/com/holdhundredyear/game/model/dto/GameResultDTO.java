package com.holdhundredyear.game.model.dto;

import com.holdhundredyear.market.entity.LoanRecord;
import lombok.Data;

import java.util.List;

/**
 * 终局结算页数据（spec §12 /api/game/result）：结局类型、存活天数、总收益率、
 * 指数涨幅、最终段位、强平记录、本局种子号、资产 vs 指数曲线、营业部最终排行榜
 * （玩家行 player=true，NPC 行解析自终局快照 npc_assets_json）。
 */
@Data
public class GameResultDTO {

    /** 结局类型：FINISHED / BANKRUPT */
    private String ending;

    /** 结束原因：NORMAL_FINISH / BANKRUPT */
    private String endReason;

    /** 存活天数（终局日 game_day） */
    private Integer endDay;

    /** 总收益率（%）=（终局净资产 / 10 万 − 1）× 100 */
    private Double totalReturnPct;

    /** 指数涨幅（%）=（终局指数 / 1000 − 1）× 100 */
    private Double indexGainPct;

    /** 最终段位代码 */
    private String finalTier;

    /** 最终段位中文名 */
    private String finalTierName;

    /** 本局世界种子号（字符串下发：long 超出 JS 安全整数范围，number 会丢精度） */
    private String worldSeed;

    /** 强平流水（loan_type=LIQUIDATE，按 gameDay,id 升序） */
    private List<LoanRecord> liquidations;

    /** 资产 vs 指数曲线（game_day 0..endDay 升序） */
    private List<CurvePoint> curve;

    /** 营业部最终排行榜（按资产降序，玩家行 player=true） */
    private List<RankRow> rankings;

    /** 曲线点：某交易日净资产与指数。 */
    @Data
    public static class CurvePoint {

        private Integer gameDay;

        private Double netAsset;

        private Double indexValue;
    }

    /** 排行榜行：NPC 行来自终局快照 JSON，玩家行 player=true。 */
    @Data
    public static class RankRow {

        private String name;

        private Double asset;

        private String status;

        private boolean player;
    }
}
