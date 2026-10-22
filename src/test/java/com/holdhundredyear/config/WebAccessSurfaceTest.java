package com.holdhundredyear.config;

import com.holdhundredyear.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 鉴权面的边界（打包给朋友玩的关键）：只有 /api/** 需要登录，其余一律放行。
 *
 * <p>为什么值得单独锁住：单个 jar 同时发前端页面和接口，浏览器直接打开 /market 或刷新
 * /stock/3 都要能拿到 index.html。以前 anyRequest().authenticated() 会把首页与 js/css
 * 一起挡成 401，页面白屏；反过来如果把 /api 也放开，任何人都能替别人下单。
 * 两个方向各断言一次，谁也别忘了谁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebAccessSurfaceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiEndpointsStillRequireLogin() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/stocks")));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(),
                "/api/** must stay authenticated, body=" + body);
    }

    @Test
    void frontendRoutesAndStaticAssetsAreNotBlocked() throws Exception {
        // 没有前端产物时这些请求会是 404，但绝不能是 401：401 意味着 jar 里的页面打不开
        for (String path : new String[]{"/", "/saves", "/market", "/stock/3", "/assets/", "/favicon.ico"}) {
            int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertNotEquals(401, status, "GET " + path + " must not be blocked by security (got " + status + ")");
            assertNotEquals(403, status, "GET " + path + " must not be forbidden (got " + status + ")");
        }
    }

    @Test
    void saveRackIsOpenWithoutToken() throws Exception {
        // 这一版没有账号系统：存档架就是入口，必须匿名可列
        JsonNode body = json(mockMvc.perform(get("/api/saves")));
        assertEquals(0, body.path("code").asInt(),
                "the save rack must be reachable anonymously, body=" + body);
    }

    @Test
    void runtimeInfoIsOpenWithoutToken() throws Exception {
        // 存档架上的"手机一起玩"要能在进游戏前拿到内网地址
        JsonNode body = json(mockMvc.perform(get("/api/runtime/info")));
        assertEquals(0, body.path("code").asInt(),
                "runtime info must be reachable anonymously, body=" + body);
        assertNotEquals(0, body.path("data").path("port").asInt(),
                "runtime info must expose the actual port");
    }

    @Test
    void spaFallbackNeverSwallowsBackendPaths() throws Exception {
        Resource location = new ClassPathResource("static");
        WebStaticConfig.SpaResourceResolver resolver = new WebStaticConfig.SpaResourceResolver();
        assertNull(resolver.getResource("api/stocks", location),
                "api paths must fall through to the controllers, not to index.html");
        assertNull(resolver.getResource("ws/query", location),
                "ws handshake must not be answered with the SPA shell");
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
