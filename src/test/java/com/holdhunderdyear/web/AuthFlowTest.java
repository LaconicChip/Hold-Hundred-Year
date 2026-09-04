package com.holdhunderdyear.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册/登录/JWT 鉴权全链路集成测试（hold_year_test 库，方法级事务自动回滚）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerWithUniqueUsernameSucceeds() throws Exception {
        JsonNode body = json(register(unique("reg"), "password123")
                .andExpect(status().isOk()));

        assertEquals(0, body.path("code").asInt(), "R.code must be 0, body=" + body);
        assertTrue(body.path("data").path("userId").asLong() > 0, "userId must be positive");
    }

    @Test
    void registerDuplicateUsernameReturnsUsernameExists() throws Exception {
        String username = unique("dup");
        register(username, "password123").andExpect(status().isOk());

        JsonNode body = json(register(username, "password456")
                .andExpect(status().isBadRequest()));

        assertEquals(1014, body.path("code").asInt(), "USERNAME_EXISTS, body=" + body);
    }

    @Test
    void registerWithTwoCharUsernameReturnsParamError() throws Exception {
        JsonNode body = json(register("ab", "password123")
                .andExpect(status().isBadRequest()));

        assertEquals(1015, body.path("code").asInt(), "PARAM_ERROR, body=" + body);
    }

    @Test
    void loginWithWrongPasswordReturnsLoginFailed() throws Exception {
        String username = unique("login");
        register(username, "password123").andExpect(status().isOk());

        JsonNode body = json(login(username, "wrong-password")
                .andExpect(status().isBadRequest()));

        assertEquals(1017, body.path("code").asInt(), "LOGIN_FAILED, body=" + body);
    }

    @Test
    void loginWithCorrectPasswordReturnsToken() throws Exception {
        String username = unique("ok");
        register(username, "password123").andExpect(status().isOk());

        JsonNode body = json(login(username, "password123")
                .andExpect(status().isOk()));

        assertEquals(0, body.path("code").asInt(), "R.code must be 0, body=" + body);
        assertFalse(body.path("data").path("token").asString().isEmpty(), "token must not be empty");
    }

    @Test
    void accessMeWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized()));

        assertEquals(1001, body.path("code").asInt(), "UNAUTHORIZED, body=" + body);
    }

    @Test
    void accessMeWithGarbageTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer this.is.not.a.jwt"))
                .andExpect(status().isUnauthorized()));

        assertEquals(1001, body.path("code").asInt(), "UNAUTHORIZED, body=" + body);
    }

    @Test
    void accessMeWithBearerTokenReturnsUserIdentity() throws Exception {
        String username = unique("me");
        long registeredUserId = json(register(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("userId").asLong();

        String token = json(login(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("token").asString();

        JsonNode body = json(mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));

        assertEquals(0, body.path("code").asInt(), "R.code must be 0, body=" + body);
        assertEquals(registeredUserId, body.path("data").path("userId").asLong(),
                "userId must match the registered one");
        assertEquals(username, body.path("data").path("username").asString());
    }

    @Test
    void getNonexistentPathReturnsNotFound() throws Exception {
        String username = unique("nf");
        register(username, "password123").andExpect(status().isOk());
        String token = json(login(username, "password123")
                .andExpect(status().isOk()))
                .path("data").path("token").asString();

        JsonNode body = json(mockMvc.perform(get("/api/nonexistent")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()));

        assertEquals(1018, body.path("code").asInt(), "NOT_FOUND, body=" + body);
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
