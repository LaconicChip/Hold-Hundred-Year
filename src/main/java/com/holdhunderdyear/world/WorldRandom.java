package com.holdhunderdyear.world;

import java.util.Random;

public final class WorldRandom {

    public static Random of(long seed, String salt) {
        return new Random(seed ^ salt.hashCode() * 0x9E3779B97F4A7C15L);
    }

    private WorldRandom() {
    }
}
