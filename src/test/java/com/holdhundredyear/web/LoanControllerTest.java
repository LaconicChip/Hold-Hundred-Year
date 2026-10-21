package com.holdhundredyear.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.common.JwtUtil;
import com.holdhundredyear.market.MarketStateService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 融资接口集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖：未认证借款被 JWT 门槛拦下（401 + UNAUTHORIZED）、认证后借款走通全链路
 * 并返回操作后余额/负债/剩余额度（LoanResultDTO 成功冒烟）、缺 amount 命中
 * @Valid 校验（400 + PARAM_ERROR）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoanControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void borrowWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(post("/api/loan/borrow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("amount", 1000))))
                .andExpect(status().isUnauthorized()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(), "UNAUTHORIZED, body=" + body);
    }

    @Test
    void borrowReturnsReceiptWithUpdatedBalanceAndDebt() throws Exception {
        long userId = newSave("loan");
        String token = tokenOf(userId);

        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setWorldSeed(20260904L);
        userGameStateMapper.updateById(gs);
        marketStateService.startDay(userId);

        JsonNode body = json(mockMvc.perform(post("/api/loan/borrow")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("amount", 50000))))
                .andExpect(status().isOk()));

        assertEquals(0, body.path("code").asInt(), "borrow must succeed, body=" + body);
        assertEquals(0, new BigDecimal("150000.00").compareTo(
                        body.path("data").path("balance").decimalValue()),
                "100000 + 50000, body=" + body);
        assertEquals(0, new BigDecimal("50000.00").compareTo(
                        body.path("data").path("debt").decimalValue()),
                "debt must equal the borrowed amount, body=" + body);
        assertEquals(0, new BigDecimal("50000.00").compareTo(
                        body.path("data").path("borrowable").decimalValue()),
                "assets 150000 - debt 50000 - debt*1.0 = 50000, body=" + body);
    }

    @Test
    void borrowWithMissingAmountReturnsParamError() throws Exception {
        long userId = newSave("loan");
        String token = tokenOf(userId);

        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setWorldSeed(20260904L);
        userGameStateMapper.updateById(gs);
        marketStateService.startDay(userId);

        JsonNode body = json(mockMvc.perform(post("/api/loan/borrow")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of())))
                .andExpect(status().isBadRequest()));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), body.path("code").asInt(),
                "missing amount must hit the @Valid gate, body=" + body);
    }

    private static String unique(String suffix) {
        return "u" + System.nanoTime() + "_" + suffix;
    }

    /** 直接播种一份存档（账号系统已删除，测试不再走注册/登录接口）。 */
    private long newSave(String suffix) {
        return worldSeeder.seedUser(unique(suffix), "$2a$10$bcrypthash");
    }

    private String tokenOf(long userId) {
        return jwtUtil.generate(userId);
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
