package com.holdhundredyear.game.content;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹幕文案池静态校验（纯 JUnit，无 Spring）：总量 ≥150、每条非空白、无 emoji、
 * 类别间去重后仍 ≥120（防大量重复凑数）。
 */
class DanmuTextsTest {

    /** emoji 常见码点区间（与 spec 正则 [\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}] 等价）。
     *  Java 码点级判定，避免代理对/正则 flag 边界问题。 */
    private static final int[][] EMOJI_RANGES = {
            {0x1F300, 0x1FAFF},
            {0x2600, 0x27BF},
            {0xFE0F, 0xFE0F},
    };

    private static final List<List<String>> ALL_CATEGORIES = List.of(
            DanmuTexts.INDEX_RISE,
            DanmuTexts.INDEX_DROP,
            DanmuTexts.LIMIT_UP,
            DanmuTexts.LIMIT_DOWN,
            DanmuTexts.RALLY,
            DanmuTexts.DIVE,
            DanmuTexts.RALLY_MINOR,
            DanmuTexts.DIVE_MINOR,
            DanmuTexts.FLAT,
            DanmuTexts.CHATTER);

    @Test
    void totalCountIsAtLeastOneHundredFifty() {
        assertTrue(DanmuTexts.totalCount() >= 150,
                "danmu pool must hold at least 150 texts, got " + DanmuTexts.totalCount());
    }

    /**
     * 日常闲聊池是"每时每刻都有人在聊"的那部分（2026-10-20）：玩家打开任意一只票的股吧，
     * 看到的绝大多数帖子应该是这种话，而不是清一色的涨跌播报。
     */
    @Test
    void chatterPoolIsBigEnoughToKeepEveryStockTalking() {
        assertTrue(DanmuTexts.CHATTER.size() >= 200,
                "chatter pool must hold at least 200 lines, got " + DanmuTexts.CHATTER.size());
    }

    @Test
    void everyDanmuTextIsNonBlank() {
        for (String text : allTexts()) {
            assertFalse(text.isBlank(), "blank danmu text found");
            assertFalse(text.trim().isEmpty(), "whitespace-only danmu text found");
        }
    }

    @Test
    void noDanmuTextContainsEmoji() {
        for (String text : allTexts()) {
            assertFalse(hasEmoji(text), "emoji found in danmu: [" + text + "]");
        }
    }

    @Test
    void dedupedAcrossCategoriesStaysAboveThreshold() {
        Set<String> seen = new HashSet<>();
        for (String text : allTexts()) {
            seen.add(text);
        }
        assertTrue(seen.size() >= 120,
                "deduped danmu pool must stay >= 120, got " + seen.size());
    }

    /** 全部类别文案展平。 */
    private static List<String> allTexts() {
        List<String> texts = new ArrayList<>();
        for (List<String> category : ALL_CATEGORIES) {
            texts.addAll(category);
        }
        return texts;
    }

    /** 码点级 emoji 检测（覆盖代理对 emoji、雅符号、变体选择器）。 */
    private static boolean hasEmoji(String s) {
        for (int cp : s.codePoints().toArray()) {
            for (int[] range : EMOJI_RANGES) {
                if (cp >= range[0] && cp <= range[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void allUnusedIdiom() {
        // 防"未使用"告警：确保 allTexts 真实遍历到各类别
        assertEquals(DanmuTexts.totalCount(), allTexts().size(), "allTexts must cover every category");
    }
}