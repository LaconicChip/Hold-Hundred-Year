package com.holdhundredyear.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.ErrorCode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 市场接口集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖 /api/market 的 REST 语义：未认证被 JWT 门槛拦下（401 + UNAUTHORIZED）、
 * state 聚合返回、skip 从新世界快进收官第 1 日并返回当日 summary、
 * 终局态（BANKRUPT）skip 拒绝（400 + FINISHED_STATE）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MarketControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Test
    void stateWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/market/state"))
                .andExpect(status().isUnauthorized()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(), "UNAUTHORIZED, body=" + body);
    }

    @Test
    void stateReturnsAggregatedMarketState() throws Exception {
        String token = registerAndLogin("mk_state1");

        JsonNode body = json(mockMvc.perform(get("/api/market/state")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));

        assertEquals(0, body.path("code").asInt(), "state must succeed, body=" + body);
        JsonNode data = body.path("data");
        assertEquals("NOT_STARTED", data.path("marketState").asString());
        assertEquals(1, data.path("currentDay").asInt());
        assertEquals(1, data.path("year").asInt());
        assertEquals(1, data.path("dayInYear").asInt());
        assertTrue(data.path("worldSeed").asLong() > 0);
    }

    @Test
    void skipOnFreshWorldReturnsClosedDaySummary() throws Exception {
        String token = registerAndLogin("mk_skip1");

        JsonNode body = json(mockMvc.perform(post("/api/market/skip")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));

        assertEquals(0, body.path("code").asInt(), "skip must succeed, body=" + body);
        JsonNode data = body.path("data");
        assertEquals(1, data.path("gameDay").asInt(), "first skip closes day 1");
        assertTrue(!data.path("comment").asString().isBlank(), "skip response must carry a comment");
    }

    @Test
    void skipAfterBankruptIsRejectedWithFinishedState() throws Exception {
        String username = unique("mk_bank1");
        long userId = json(register(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("userId").asLong();
        String token = json(login(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("token").asString();

        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setMarketState("BANKRUPT");
        userGameStateMapper.updateById(gs);

        JsonNode body = json(mockMvc.perform(post("/api/market/skip")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()));

        assertEquals(ErrorCode.FINISHED_STATE.getCode(), body.path("code").asInt(),
                "terminal state must reject skip, body=" + body);
    }

    private String registerAndLogin(String suffix) throws Exception {
        String username = unique(suffix);
        json(register(username, "password123").andExpect(status().isOk()));
        return json(login(username, "password123").andExpect(status().isOk()))
                .path("data").path("token").asString();
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
