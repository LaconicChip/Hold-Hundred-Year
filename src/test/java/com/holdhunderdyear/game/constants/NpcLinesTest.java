package com.holdhunderdyear.game.constants;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC 台词库校验（纯单测）：四个角色线场景与八位 NPC 的闲聊池都要有足够条数、
 * 非空白、无 emoji；荐股台词必须保留 {stock} 槽位，否则换不进真实票名。
 */
class NpcLinesTest {

    private static final Pattern EMOJI =
            Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");

    @Test
    void everySceneHasSeveralLines() {
        for (NpcLines.Scene scene : NpcLines.Scene.values()) {
            List<String> lines = NpcLines.scene(scene);
            assertTrue(lines.size() >= 5, "scene " + scene + " needs at least 5 lines");
            for (String line : lines) {
                assertFalse(line.isBlank(), "scene " + scene + " has blank line");
                assertFalse(EMOJI.matcher(line).find(), "scene " + scene + " line contains emoji: " + line);
            }
        }
        assertTrue(NpcLines.scene(NpcLines.Scene.DAMA_RECOMMEND).stream()
                        .allMatch((line) -> line.contains("{stock}")),
                "every 张大妈 recommend line must name the stock through the {stock} slot");
    }

    @Test
    void everyNpcHasChatterAndNothingRepeats() {
        Set<String> seen = new HashSet<>();
        for (NpcDefs.Npc npc : NpcDefs.ALL) {
            List<String> lines = NpcLines.chatter(npc.code());
            assertEquals(5, lines.size(), "npc " + npc.code() + " must own 5 chatter lines");
            for (String line : lines) {
                assertTrue(line.startsWith(npc.name() + "："), "chatter must be voiced by its owner: " + line);
                assertFalse(EMOJI.matcher(line).find(), "chatter contains emoji: " + line);
                assertTrue(seen.add(line), "duplicated npc line: " + line);
            }
        }
        assertTrue(NpcLines.chatter("NO_SUCH_NPC").isEmpty(), "unknown npc code must yield an empty pool");
    }
}
