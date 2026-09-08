package com.holdhunderdyear.game.model.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worldSeed 回归测试（验收发现：终局页种子号显示丢精度）。
 * 种子是 1..Long.MAX_VALUE 的 long，而 JS Number 只能安全表示 2^53-1——
 * 若 DTO 以 JSON number 下发，前端显示的种子号会与库中实际值不符。
 * 约束：GameResultDTO / MarketStateDTO 的 worldSeed 必须以 JSON 字符串序列化。
 */
class SeedSerializationTest {

    /** 2^53+1：作为 JSON number 传给 JS 会退化为 2^53，是经典精度丢失样本。 */
    private static final long UNSAFE_SEED = 9007199254740993L;

    /** Long.MAX_VALUE：随机种子理论上可取到的上界。 */
    private static final long MAX_SEED = Long.MAX_VALUE;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void gameResultSeedMustSerializeAsString() {
        GameResultDTO dto = new GameResultDTO();
        dto.setWorldSeed(String.valueOf(UNSAFE_SEED));
        String json = mapper.writeValueAsString(dto);
        assertTrue(json.contains("\"worldSeed\":\"" + UNSAFE_SEED + "\""),
                "GameResultDTO.worldSeed must be a JSON string, got: " + json);
    }

    @Test
    void gameResultSeedSurvivesMaxLong() {
        GameResultDTO dto = new GameResultDTO();
        dto.setWorldSeed(String.valueOf(MAX_SEED));
        String json = mapper.writeValueAsString(dto);
        assertTrue(json.contains("\"worldSeed\":\"" + MAX_SEED + "\""),
                "GameResultDTO.worldSeed must survive Long.MAX_VALUE, got: " + json);
    }

    @Test
    void marketStateSeedMustSerializeAsString() {
        MarketStateDTO dto = new MarketStateDTO();
        dto.setWorldSeed(String.valueOf(UNSAFE_SEED));
        String json = mapper.writeValueAsString(dto);
        assertTrue(json.contains("\"worldSeed\":\"" + UNSAFE_SEED + "\""),
                "MarketStateDTO.worldSeed must be a JSON string, got: " + json);
    }
}
