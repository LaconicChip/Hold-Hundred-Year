package com.holdhunderdyear.game.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 营业部日排行榜（Task 10.3，spec §11 GET /api/market/leaderboard）：
 * 与终局 rankings 同源——NPC 行解析自当日快照 npc_assets_json（code/name/asset/status），
 * 玩家行以当日快照净资产追加，按资产降序。
 */
@Data
public class LeaderboardDTO {

    /** 交易日 */
    private int gameDay;

    /** 排行榜行（按资产降序） */
    private List<Row> rows;

    /** 排行榜行：NPC 行来自快照 JSON，玩家行 isPlayer=true（name 固定"您"）。 */
    @Data
    public static class Row {

        private String name;

        private double asset;

        private String status;

        /** 是否玩家行（Boolean 使 JSON 字段名为 isPlayer） */
        private Boolean isPlayer;
    }
}
