package com.holdhunderdyear.config;

import com.holdhunderdyear.common.JwtUtil;
import com.holdhunderdyear.ws.SimplePrincipal;
import io.jsonwebtoken.JwtException;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket(STOMP) 配置（Task 10.2，spec §9）：注册 /ws 握手端点（本地联调放开全部来源），
 * simple broker 服务 /queue（承接 /user/{id}/queue/* 翻译后的用户队列），user 目标前缀
 * /user——推送走 per-user 队列 {@code /user/{userId}/queue/*}。客户端入口通道挂 JWT 握手
 * 拦截器：CONNECT 时从 native header "token" 解析 userId 建立 Principal，非法/缺失 token
 * 拒绝握手（SecurityConfig 已放行 /ws，鉴权下沉到本层 STOMP 握手）。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtil jwtUtil;

    public WebSocketConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 前缀必须为 /queue：用户目的地 /user/{id}/queue/* 经 DefaultUserDestinationResolver
        // 翻译为会话内 /queue/* 后由 simple broker 承接（spec §9 /user/queue/{通道}）。
        // 若启用 /user/queue 前缀，同一订阅会同时命中用户翻译与直配（双重注册、重复推送）。
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new JwtHandshakeInterceptor(jwtUtil));
    }

    /** STOMP 握手 JWT 拦截器（Task 10.2）：CONNECT 时校验 native header "token" 并建立
     *  Principal；可独立调用 {@link #preConnect} 做握手鉴权焦点测试。 */
    public static class JwtHandshakeInterceptor implements ChannelInterceptor {

        private final JwtUtil jwtUtil;

        public JwtHandshakeInterceptor(JwtUtil jwtUtil) {
            this.jwtUtil = jwtUtil;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                if (!preConnect(accessor)) {
                    return null; // 拒绝帧 = 拒绝 STOMP CONNECT 握手
                }
            }
            return message;
        }

        /** 校验 token 并建立 Principal；成功返回 true，失败返回 false（拒绝握手）。 */
        public boolean preConnect(StompHeaderAccessor accessor) {
            String token = accessor.getFirstNativeHeader("token");
            if (token == null || token.isBlank()) {
                return false;
            }
            try {
                Long userId = jwtUtil.parseUserId(token);
                accessor.setUser(new SimplePrincipal(userId));
                return true;
            } catch (JwtException | IllegalArgumentException e) {
                return false;
            }
        }
    }
}