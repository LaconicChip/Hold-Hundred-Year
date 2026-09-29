package com.holdhunderdyear.game.model.enums;

import lombok.Getter;

/**
 * 108 天的 11 个纪元（spec §3）。声明顺序即 eraIndex 顺序：ordinal + 1 == eraIndex。
 */
@Getter
public enum EraCode {

    BULL_START("牛市启动", "低估值打底，券商先动。这种行情比的是谁敢拿住，不是谁跑得快。"),
    EUPHORIA("全民疯牛", "连菜场都在讲股票。指数涨得越急，越要记得自己是怎么进来的。"),
    CRASH("股灾降临", "千股跌停来了。杠杆是双刃剑，担保比例会先于信仰跌破 120%。"),
    POST_CRASH("灾后震荡", "跌透了会反弹，反弹完接着磨。缩量磨底，磨的是耐心。"),
    CIRCUIT_BREAKER("熔断开局", "开盘即巅峰，一天提前收工。挂单之前，先把规则看清楚。"),
    BLUE_CHIP("蓝筹白马慢牛", "喝酒吃药买白马。慢牛不喧哗，重仓的人才知道什么叫涨。"),
    TRADE_WAR("贸易战熊市", "消息面一言不合就掀桌。农业先动，科技先跌为敬。"),
    NEW_ENERGY("新能源结构牛", "指数不动，赛道疯涨。买错板块，比不买还难受。"),
    AI_BULL("AI疯牛", "算力成了新石油，故事讲得比业绩快。追高之前先想想，谁来接最后一棒。"),
    BLACK_JULY("黑7月", "七月的阴跌不讲道理。仓位重于方向，现金也是一种仓位。"),
    REBALANCE("震荡再平衡", "高低切换，风格轮动。去年的冠军今年垫底，均衡才是长期饭票。");

    private final String name;

    /** 纪元叙事（营业部体一句话）：纪元切换通知用，只影响文案、不参与任何数值与随机协议。 */
    private final String narrative;

    EraCode(String name, String narrative) {
        this.name = name;
        this.narrative = narrative;
    }
}
