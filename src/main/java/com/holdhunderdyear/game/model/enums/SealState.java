package com.holdhunderdyear.game.model.enums;

/**
 * 封板状态：NONE 未封板、UP 封涨停、DOWN 封跌停。
 * 由封板机（Phase 6）维护并写入 Quote，磁吸漂移直接作用于收益（不经 ShockQueue）。
 */
public enum SealState {

    NONE,
    UP,
    DOWN
}
