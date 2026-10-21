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
 * 交易接口集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖：未认证下单被 JWT 门槛拦下（401 + UNAUTHORIZED）、认证后非整手数量
 * 命中服务层校验链（NOT_LOT；世界须先开盘，否则会先被市场态拦下）、
 * 认证后整手买入走通全链路并返回成交明细（TradeResultDTO 成功冒烟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TradeControllerTest {

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
    void buyWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(post("/api/trade/buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("stockId", 1, "shares", 100))))
                .andExpect(status().isUnauthorized()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(), "UNAUTHORIZED, body=" + body);
    }

    @Test
    void buyWithNonLotSharesReturnsNotLot() throws Exception {
        long userId = newSave("trade");
        String token = tokenOf(userId);

        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setWorldSeed(20260904L);
        userGameStateMapper.updateById(gs);
        marketStateService.startDay(userId);

        JsonNode body = json(mockMvc.perform(post("/api/trade/buy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("stockId", 2, "shares", 50))))
                .andExpect(status().isBadRequest()));

        assertEquals(ErrorCode.NOT_LOT.getCode(), body.path("code").asInt(),
                "NOT_LOT, body=" + body);
    }

    @Test
    void buyWithValidOrderReturnsFilledDetails() throws Exception {
        long userId = newSave("trade");
        String token = tokenOf(userId);

        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        gs.setWorldSeed(20260904L);
        userGameStateMapper.updateById(gs);
        marketStateService.startDay(userId);

        JsonNode body = json(mockMvc.perform(post("/api/trade/buy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("stockId", 1, "shares", 200))))
                .andExpect(status().isOk()));

        assertEquals(0, body.path("code").asInt(), "valid lot order must succeed, body=" + body);
        assertEquals(200, body.path("data").path("shares").asInt(), "body=" + body);
        assertEquals(0, new BigDecimal("168.00").compareTo(
                        body.path("data").path("price").decimalValue()),
                "day-1 base price, body=" + body);
        assertEquals(0, new BigDecimal("33600.00").compareTo(
                        body.path("data").path("amount").decimalValue()),
                "168.00 * 200 = 33600.00, body=" + body);
        assertEquals(0, new BigDecimal("10.08").compareTo(
                        body.path("data").path("commission").decimalValue()),
                "33600.00 * 0.0003 = 10.08, body=" + body);
        assertEquals(0, new BigDecimal("66389.92").compareTo(
                        body.path("data").path("balance").decimalValue()),
                "100000.00 - 33600.00 - 10.08 = 66389.92, body=" + body);
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
