package com.holdhunderdyear.game.model.enums;

import lombok.Getter;

/**
 * 一局的游戏档案（新建局时选定，存进 user_game_state.game_mode，整局不变）。
 *
 * <p>标准模式就是项目原本的节奏（3 年 × 24 交易日、1 秒/跳、涨跌停 ±10%），
 * 快速模式把年长与每天墙钟各对半开（36 个交易日、约 30 分钟一局），并把日内
 * 波动上限抬到 ±20%（ST 抬到 ±10%）——短局要更刺激，而不是单纯把剧情剪短。
 *
 * <p>跳数不变（仍是上午 45 + 下午 45），所以 11 个纪元、全部剧本日类型、事件工厂、
 * 封板/炸板、T+1、停牌、除权、打新 D+2/D+4、融资三档这些机制一个都不少，
 * 只是每天的时间轴被压短、单日振幅被放大。
 */
@Getter
public enum GameMode {

    STANDARD("标准模式", 24, 1000, 10000, 0.10, 3, false, "三年 72 个交易日 · 约 2 小时 · 涨跌停 ±10%"),
    QUICK("快速模式", 12, 500, 5000, 0.20, 2, true, "三年 36 个交易日 · 约 30 分钟 · 涨跌停 ±20%");

    /** 一局固定的游戏年数。 */
    public static final int YEARS = 3;

    private final String label;
    private final int daysPerYear;
    private final int tickMs;
    private final int lunchMs;
    /** 普通股涨跌停幅度（ST 取其一半）。 */
    private final double limitPct;
    /**
     * 单个纪元的最短天数：标准模式一年 24 天装 4 个纪元，3 天下限有充分余量；
     * 快速模式一年 12 天装 4 个纪元正好用满（4×3），抖动会把某段挤到 2 天，
     * 因此下限放到 2，保证 11 个纪元既不重叠也不留缝地全部塞进去。
     */
    private final int minEraLen;
    /**
     * 黑7月崩盘日的撒点窗口要不要铺满整个纪元（由 ScriptDayScheduler 消费）：
     * 标准模式只在纪元 10 前半段崩、后半段阴跌，还原"跌透了的三个月"；
     * 快速模式纪元 10 实测只有 2~3 天，再只取前半段就只剩 1 个崩盘日——
     * "黑7月崩盘周"演成一天。短局没有阴跌的时间预算，整段都可以崩。
     */
    private final boolean crashWindowFullEra;
    private final String description;

    GameMode(String label, int daysPerYear, int tickMs, int lunchMs, double limitPct,
            int minEraLen, boolean crashWindowFullEra, String description) {
        this.label = label;
        this.daysPerYear = daysPerYear;
        this.tickMs = tickMs;
        this.lunchMs = lunchMs;
        this.limitPct = limitPct;
        this.minEraLen = minEraLen;
        this.crashWindowFullEra = crashWindowFullEra;
        this.description = description;
    }

    /** 本局收官日（满该日正常结束，spec §3）。 */
    public int totalDays() {
        return daysPerYear * YEARS;
    }

    /** ST 股涨跌停幅度：普通股的一半（标准 5%、快速 10%）。 */
    public double stLimitPct() {
        return limitPct / 2;
    }

    /** 一个交易日的现实秒数（90 跳 + 午休）。 */
    public int daySeconds() {
        return (90 * tickMs + lunchMs) / 1000;
    }

    /** 按 code 解析；null/未知一律回退标准模式（老库与老客户端兼容）。 */
    public static GameMode of(String code) {
        for (GameMode mode : values()) {
            if (mode.name().equalsIgnoreCase(code == null ? "" : code.trim())) {
                return mode;
            }
        }
        return STANDARD;
    }
}
