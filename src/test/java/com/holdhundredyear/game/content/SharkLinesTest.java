package com.holdhundredyear.game.content;

import com.holdhundredyear.world.WorldRandom;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高利贷台词校验（纯单测）：7 个场景都要有话、无 emoji、无占位、彼此不重复，
 * 并且同一种子同一天抽到的是同一句（催债话术走 WorldRandom，不能靠 Math.random）。
 */
class SharkLinesTest {

    private static final Pattern EMOJI =
            Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");

    @Test
    void everySceneHasEnoughLines() {
        for (SharkLines.Scene scene : SharkLines.Scene.values()) {
            List<String> lines = SharkLines.pool(scene);
            assertTrue(lines.size() >= 2, "scene " + scene + " needs at least 2 lines, got " + lines.size());
            for (String line : lines) {
                assertFalse(line.isBlank(), "blank line in scene " + scene);
                assertFalse(EMOJI.matcher(line).find(), "emoji in scene " + scene + ": " + line);
                for (String token : List.of("TODO", "XXX", "占位")) {
                    assertFalse(line.contains(token), "placeholder in " + scene + ": " + line);
                }
            }
        }
    }

    @Test
    void noLineIsReusedAcrossScenes() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new java.util.ArrayList<>();
        for (SharkLines.Scene scene : SharkLines.Scene.values()) {
            for (String line : SharkLines.pool(scene)) {
                if (!seen.add(line)) {
                    duplicates.add(scene + " :: " + line);
                }
            }
        }
        assertTrue(duplicates.isEmpty(), "repeated shark copy kills the mood: " + duplicates);
    }

    @Test
    void pickIsDeterministicForTheSameSeedAndDay() {
        Random first = WorldRandom.of(20261010L, "shark7");
        Random second = WorldRandom.of(20261010L, "shark7");
        for (SharkLines.Scene scene : SharkLines.Scene.values()) {
            assertEquals(SharkLines.pick(scene, first), SharkLines.pick(scene, second),
                    "scene " + scene + " must pick the same line for the same seed and day");
        }
    }

    @Test
    void scenesCoverTheWholeLifecycle() {
        Set<String> names = Arrays.stream(SharkLines.Scene.values()).map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.containsAll(Set.of("BORROWED", "CHATTER", "DUE_SOON", "DUE_TODAY", "OVERDUE",
                        "LIQUIDATED", "CLEARED", "WARNING")),
                "the board needs a line for every stage of the loan, got " + names);
    }
}
