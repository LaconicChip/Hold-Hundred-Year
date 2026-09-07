package com.holdhunderdyear.config;

import com.holdhunderdyear.common.JwtUtil;
import com.holdhunderdyear.ws.SimplePrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * WebSocket 配置聚焦测试（Task 10.2）：/ws 端点已注册、simple broker 服务 /user/queue、
 * user 目标前缀为 /user，以及 STOMP 握手 JWT 鉴权（合法 token 建立 userId Principal，
 * 非法/缺失 token 拒绝握手）。端到端 STOMP 建连在单测环境难搭，故聚焦配置与握手要点。
 */
@SpringBootTest
class WebSocketConfigTest {

    @Autowired(required = false)
    private WebSocketHandlerMapping webSocketHandlerMapping;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void wsEndpointIsRegisteredInHandlerMapping() {
        assertNotNull(webSocketHandlerMapping, "a WebSocketHandlerMapping bean must back STOMP endpoints");
        assertTrue(webSocketHandlerMapping.getUrlMap().containsKey("/ws"),
                "STOMP endpoint /ws must be registered for the handshake");
    }

    @Test
    void brokerServesUserQueueDestinationPrefix() {
        WebSocketConfig config = new WebSocketConfig(mock(JwtUtil.class));
        ExposedRegistry registry = new ExposedRegistry(mock(SubscribableChannel.class), mock(MessageChannel.class));
        config.configureMessageBroker(registry);

        // 用户目的地 /user/{id}/queue/* 会被 DefaultUserDestinationResolver 翻译为
        // 会话内 /queue/*——broker 必须启用 "/queue" 前缀承接；"/user/queue" 前缀会让
        // 同一订阅既走用户翻译又走直配（双重注册、重复推送），必须禁用。
        Collection<String> prefixes = registry.brokerDestinationPrefixes();
        assertTrue(prefixes.contains("/queue"),
                "simple broker must serve /queue destinations (user-dest translated), got " + prefixes);
        assertFalse(prefixes.contains("/user/queue"),
                "broker must NOT serve /user/queue directly (double registration + duplicate pushes)");
        assertEquals("/user", registry.userDestinationPrefix(),
                "user destination prefix must be /user for per-user queues");
    }

    @Test
    void validJwtEstablishesUserPrincipal() {
        Long userId = 42L;
        WebSocketConfig.JwtHandshakeInterceptor interceptor =
                new WebSocketConfig.JwtHandshakeInterceptor(jwtUtil); // real JwtUtil.parseUserId
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("token", jwtUtil.generate(userId));

        assertTrue(interceptor.preConnect(accessor), "a valid token must pass the handshake");
        assertNotNull(accessor.getUser(), "a Principal must be established on CONNECT");
        assertEquals(userId, ((SimplePrincipal) accessor.getUser()).userId(),
                "the Principal must carry the userId from the token");
    }

    @Test
    void missingOrInvalidTokenRejectsHandshake() {
        WebSocketConfig.JwtHandshakeInterceptor interceptor =
                new WebSocketConfig.JwtHandshakeInterceptor(jwtUtil);

        StompHeaderAccessor missing = StompHeaderAccessor.create(StompCommand.CONNECT);
        assertFalse(interceptor.preConnect(missing), "a missing token must be rejected");

        StompHeaderAccessor invalid = StompHeaderAccessor.create(StompCommand.CONNECT);
        invalid.setNativeHeader("token", "not-a-jwt");
        assertFalse(interceptor.preConnect(invalid), "an invalid token must be rejected");
    }

    /** 暴露 MessageBrokerRegistry 受保护访问器，以在配置层断言 broker 路由与 user 前缀。 */
    private static class ExposedRegistry extends MessageBrokerRegistry {

        private final SubscribableChannel inbound;

        ExposedRegistry(SubscribableChannel inbound, MessageChannel outbound) {
            super(inbound, outbound);
            this.inbound = inbound;
        }

        Collection<String> brokerDestinationPrefixes() {
            SimpleBrokerMessageHandler handler = getSimpleBroker(inbound);
            return handler.getDestinationPrefixes();
        }

        String userDestinationPrefix() {
            return getUserDestinationPrefix();
        }
    }
}