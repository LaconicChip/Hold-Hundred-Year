package com.holdhundredyear.game.model.enums;

/**
 * 交易日状态机（spec §3）：NOT_STARTED 起手开盘进入 MORNING_OPEN，
 * 一日内 MORNING_OPEN → LUNCH_BREAK → AFTERNOON_OPEN → SETTLING → CLOSED；
 * CLOSED 可再 startDay 开启下一交易日（current_day+1）；
 * BANKRUPT/FINISHED 为终态，任何开盘操作拒绝。
 */
public enum MarketState {

    NOT_STARTED,
    MORNING_OPEN,
    LUNCH_BREAK,
    AFTERNOON_OPEN,
    SETTLING,
    CLOSED,
    BANKRUPT,
    FINISHED
}
