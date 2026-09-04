package com.holdhunderdyear.game.model.enums;

import lombok.Getter;

/**
 * 108 天的 11 个纪元（spec §3）。声明顺序即 eraIndex 顺序：ordinal + 1 == eraIndex。
 */
@Getter
public enum EraCode {

    BULL_START("牛市启动"),
    EUPHORIA("全民疯牛"),
    CRASH("股灾降临"),
    POST_CRASH("灾后震荡"),
    CIRCUIT_BREAKER("熔断开局"),
    BLUE_CHIP("蓝筹白马慢牛"),
    TRADE_WAR("贸易战熊市"),
    NEW_ENERGY("新能源结构牛"),
    AI_BULL("AI疯牛"),
    BLACK_JULY("黑7月"),
    REBALANCE("震荡再平衡");

    private final String name;

    EraCode(String name) {
        this.name = name;
    }
}
