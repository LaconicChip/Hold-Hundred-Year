package com.holdhunderdyear.game.model.enums;

import lombok.Getter;

/**
 * 剧本日类型（spec §4.4 历史名场面、§6.5 打新）。声明顺序即 user_script_day 排期展示顺序。
 */
@Getter
public enum ScriptType {

    LIMIT_UP_ALL("千股涨停"),
    LIMIT_DOWN_ALL("千股跌停"),
    RESCUE("救市日"),
    CIRCUIT_BREAKER("熔断日"),
    SANCTION("制裁闪崩"),
    CRASH_WEEK("黑7月崩盘"),
    IPO("新股申购");

    private final String name;

    ScriptType(String name) {
        this.name = name;
    }
}
