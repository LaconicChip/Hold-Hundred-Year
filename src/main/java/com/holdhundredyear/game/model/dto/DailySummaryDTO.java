package com.holdhundredyear.game.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 单日收官总结（Task 10.3，spec §11 GET /api/market/daily-summary）：
 * 快照资金四项 + 维持担保比例 + 当日事件新闻/系统通知标题 + 一句玩梗评语 + 纪元名。
 * guardRatio 口径：debt &gt; 0 时 (cash+marketValue)/debt（含现金与持仓的担保比例），
 * 无负债返回 0（表示无杠杆敞口，而非无穷大）。
 */
@Data
public class DailySummaryDTO {

    /** 交易日（1..TOTAL_DAYS） */
    private int gameDay;

    /** 当日收益率（%），无快照口径时 0 */
    private double dayReturnPct;

    /** 当日指数涨跌（%） */
    private double indexDayPct;

    /** 快照现金 */
    private double cash;

    /** 快照持仓市值 */
    private double marketValue;

    /** 快照负债 */
    private double debt;

    /** 快照净资产 */
    private double netAsset;

    /** 维持担保比例：debt&gt;0 时 (cash+marketValue)/debt，无负债为 0 */
    private double guardRatio;

    /** 当日事件新闻标题（source∈{AUTHORITY,MEDIA,RUMOR,INSIDER} 且 type=EVENT，按落库序） */
    private List<String> news;

    /** 当日系统通知标题（source=SYSTEM 且 type=NOTIFY，如追保/强平/停牌/中签/收盘结算） */
    private List<String> notices;

    /** 玩梗评语：按日收益相对指数超额收益分档，经 WorldRandom.of(worldSeed,"comment"+day) 确定性抽取 */
    private String comment;

    /** 当日所属纪元中文名 */
    private String eraName;
}
