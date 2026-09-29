package com.holdhunderdyear.game.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 游戏档案取值（纯单测）：标准模式必须与压缩后的原节奏完全一致，
 * 快速模式是"年长对半 + 每天墙钟对半 + 涨跌停翻倍"，机制参数（90 跳/天）不变。
 */
class GameModeTest {

    @Test
    void standardKeepsTheShippedRhythm() {
        assertEquals(24, GameMode.STANDARD.getDaysPerYear());
        assertEquals(72, GameMode.STANDARD.totalDays());
        assertEquals(1000, GameMode.STANDARD.getTickMs());
        assertEquals(100, GameMode.STANDARD.daySeconds(), "90 跳 × 1 秒 + 午休 10 秒");
        assertEquals(0.10, GameMode.STANDARD.getLimitPct(), 1e-9);
        assertEquals(0.05, GameMode.STANDARD.stLimitPct(), 1e-9);
    }

    @Test
    void quickHalvesCalendarAndClockAndDoublesTheLimit() {
        assertEquals(12, GameMode.QUICK.getDaysPerYear());
        assertEquals(36, GameMode.QUICK.totalDays());
        assertEquals(500, GameMode.QUICK.getTickMs());
        assertEquals(50, GameMode.QUICK.daySeconds());
        assertEquals(0.20, GameMode.QUICK.getLimitPct(), 1e-9);
        assertEquals(0.10, GameMode.QUICK.stLimitPct(), 1e-9);
    }

    @Test
    void unknownOrMissingCodeFallsBackToStandard() {
        assertSame(GameMode.STANDARD, GameMode.of(null), "老客户端不传 mode 必须按标准走");
        assertSame(GameMode.STANDARD, GameMode.of(""));
        assertSame(GameMode.STANDARD, GameMode.of("nope"));
        assertSame(GameMode.QUICK, GameMode.of("quick"), "大小写不敏感");
    }
}
