package com.holdhundredyear.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 终局接口集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖：未认证查询结果/重开被 JWT 门槛拦下（401 + UNAUTHORIZED）、认证后终局
 * 重开走通全链路（restart code=0，重开后 result 返回 400 + GAME_NOT_FINISHED）、
 * 模式选择器用的 /api/game/modes 与 GameMode 档案一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GameControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Test
    void resultWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/game/result"))
                .andExpect(status().isUnauthorized()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(), "UNAUTHORIZED, body=" + body);
    }

    @Test
    void restartWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(post("/api/game/restart"))
                .andExpect(status().isUnauthorized()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(), "UNAUTHORIZED, body=" + body);
    }

    @Test
    void restartThenResultReportsFreshGame() throws Exception {
        String username = unique("game");
        long userId = json(register(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("userId").asLong();
        String token = json(login(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("token").asString();

        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setMarketState("FINISHED");
        userGameStateMapper.updateById(gs);

        JsonNode restart = json(mockMvc.perform(post("/api/game/restart")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        assertEquals(0, restart.path("code").asInt(), "restart must succeed, body=" + restart);

        JsonNode result = json(mockMvc.perform(get("/api/game/result")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()));
        assertEquals(ErrorCode.GAME_NOT_FINISHED.getCode(), result.path("code").asInt(),
                "a fresh world has no final result yet, body=" + result);
    }

    /**
     * 空 body 的 restart 必须是"沿用本局模式"。终局页去掉了模式选择器（模式在下一局
     * 第 1 天选），它发的就是空 mode；若被兜底成 STANDARD，快速模式玩家重开就被悄悄换档。
     */
    @Test
    void restartWithEmptyBodyKeepsCurrentMode() throws Exception {
        String username = unique("keepmode");
        long userId = json(register(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("userId").asLong();
        String token = json(login(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("token").asString();

        UserGameState before = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        before.setMarketState("FINISHED");
        before.setGameMode(GameMode.QUICK.name());
        userGameStateMapper.updateById(before);

        JsonNode restart = json(mockMvc.perform(post("/api/game/restart")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk()));
        assertEquals(0, restart.path("code").asInt(), "restart must succeed, body=" + restart);

        UserGameState after = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        assertEquals(GameMode.QUICK.name(), after.getGameMode(),
                "empty mode must keep this run's mode instead of falling back to STANDARD");
    }

    @Test
    void modesListEveryProfileForTheModePicker() throws Exception {
        String username = unique("modes");
        register(username, "password123");
        String token = json(login(username, "password123")).path("data").path("token").asString();

        JsonNode body = json(mockMvc.perform(get("/api/game/modes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        assertEquals(0, body.path("code").asInt(), "modes must succeed, body=" + body);

        JsonNode data = body.path("data");
        assertEquals(GameMode.values().length, data.size(), "one entry per GameMode, body=" + data);
        for (GameMode mode : GameMode.values()) {
            JsonNode entry = null;
            for (JsonNode node : data) {
                if (mode.name().equals(node.path("code").asString())) {
                    entry = node;
                }
            }
            assertNotNull(entry, "missing mode in payload: " + mode.name());
            assertEquals(mode.getLabel(), entry.path("label").asString(), "label of " + mode);
            assertEquals(mode.getDescription(), entry.path("description").asString(), "description of " + mode);
            assertEquals(mode.totalDays(), entry.path("totalDays").asInt(), "totalDays of " + mode);
        }
    }

    private static String unique(String suffix) {
        return "u" + System.nanoTime() + "_" + suffix;
    }

    private ResultActions register(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
