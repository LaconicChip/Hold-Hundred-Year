package com.holdhundredyear.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorldRandomTest {

    @Test
    void seedAndSaltFullyDetermineTheSequence() {
        List<Object> eraFirst = drawSequence(123L, "era");
        List<Object> eraSecond = drawSequence(123L, "era");

        assertEquals(eraFirst, eraSecond, "same seed and salt must reproduce the exact same sequence");

        assertNotEquals(eraFirst, drawSequence(123L, "script"), "different salt must yield a different sequence");
        assertNotEquals(eraFirst, drawSequence(456L, "era"), "different seed must yield a different sequence");
    }

    private static List<Object> drawSequence(long seed, String salt) {
        Random random = WorldRandom.of(seed, salt);
        List<Object> draws = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            switch (i % 5) {
                case 0 -> draws.add(random.nextInt(1000));
                case 1 -> draws.add(random.nextLong());
                case 2 -> draws.add(random.nextDouble());
                case 3 -> draws.add(random.nextGaussian());
                default -> draws.add(random.nextBoolean());
            }
        }
        return draws;
    }
}
