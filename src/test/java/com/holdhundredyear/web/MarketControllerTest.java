package com.holdhundredyear.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.common.JwtUtil;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.world.WorldSeeder;
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

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void stateWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/market/state"))
                .andExpect(status().isUnauthorized()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(), "UNAUTHORIZED, body=" + body);
    }

    /** 暂停/恢复：只动内存标记，可重复调用；未认证一律 401（不能让外人冻住别人的世界）。 */
    @Test
    void pauseAndResumeRequireAuthAndAreIdempotent() throws Exception {
        JsonNode anonymous = json(mockMvc.perform(post("/api/market/pause"))
                .andExpect(status().isUnauthorized()));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), anonymous.path("code").asInt(),
                "pause must require auth, body=" + anonymous);

        String token = registerAndLogin("pause");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(0, json(mockMvc.perform(post("/api/market/pause")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())).path("code").asInt(),
                    "pause must succeed on every call, attempt " + attempt);
            assertEquals(0, json(mockMvc.perform(post("/api/market/resume")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())).path("code").asInt(),
                    "resume must succeed on every call, attempt " + attempt);
        }
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
        long userId = newSave("mk_bank1");
        String token = tokenOf(userId);

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

    private String registerAndLogin(String suffix) {
        return tokenOf(newSave(suffix));
    }

    /** 直接播种一份存档（账号系统已删除，测试不再走注册/登录接口）。 */
    private long newSave(String suffix) {
        return worldSeeder.seedUser(unique(suffix), "$2a$10$bcrypthash");
    }

    private String tokenOf(long userId) {
        return jwtUtil.generate(userId);
    }

    private static String unique(String suffix) {
        return "u" + System.nanoTime() + "_" + suffix;
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
