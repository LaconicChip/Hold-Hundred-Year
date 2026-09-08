package com.holdhunderdyear.game.model.dto;

import lombok.Data;

/**
 * 市场状态聚合（Task 10.3，spec §11 GET/POST /api/market/state|start）：
 * 状态机当前值、交易日历（一游戏年 36 个交易日的年/日换算）、当日所属纪元、
 * 玩家段位、盘中打点与半场倒计时、本局世界种子。
 */
@Data
public class MarketStateDTO {

    /** 交易日状态机值（NOT_STARTED/MORNING_OPEN/LUNCH_BREAK/AFTERNOON_OPEN/SETTLING/CLOSED/BANKRUPT/FINISHED） */
    private String marketState;

    /** 当前交易日（1..108） */
    private int currentDay;

    /** 游戏年（(currentDay-1)/36 + 1） */
    private int year;

    /** 年内交易日（(currentDay-1)%36 + 1） */
    private int dayInYear;

    /** 当日所属纪元代码 */
    private String eraCode;

    /** 当日所属纪元中文名 */
    private String eraName;

    /** 玩家段位代码 */
    private String tier;

    /** 盘中已走跳数 */
    private int tickIndex;

    /** 盘中倒计时（盘中 = 90 - tickIndex，其余状态固定 0） */
    private int countdownTicks;

    /** 本局世界种子号（字符串下发：long 超出 JS 安全整数范围，number 会丢精度） */
    private String worldSeed;
}
