package com.holdhunderdyear.ws;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * WebSocket 会话生命周期监听（spec §3 离线暂停 / §9）：STOMP CONNECT 建立即按
 * Principal（{@link SimplePrincipal}，由 JwtHandshakeInterceptor 建立）把会话登记进
 * {@link WsSessionRegistry}——调度器（MarketScheduler）只推进在线用户的世界，本监听
 * 是"在线"判定的唯一生产数据源；DISCONNECT 时注销对应会话（多标签页各持会话，
 * 全部断开才离线）。
 *
 * <p>无 Principal 或非本系统的 Principal 的会话一律忽略（无法归属 userId）。
 */
@Component
public class WsLifecycleListener {

    private final WsSessionRegistry registry;

    public WsLifecycleListener(WsSessionRegistry registry) {
        this.registry = registry;
    }

    /** CONNECT：登记 userId → sessionId（幂等）。 */
    @EventListener
    public void onConnect(SessionConnectEvent event) {
        Long userId = userIdOf(event.getUser());
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (userId != null && sessionId != null) {
            registry.register(userId, sessionId);
        }
    }

    /** DISCONNECT：注销该会话；该用户无剩余会话时标记离线。 */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Long userId = userIdOf(event.getUser());
        if (userId != null) {
            registry.onSessionDisconnected(userId, event.getSessionId());
        }
    }

    /** 从 Principal 提取 userId；非 SimplePrincipal 视为不可归属会话，返回 null。 */
    private Long userIdOf(java.security.Principal principal) {
        if (principal instanceof SimplePrincipal simple) {
            return simple.userId();
        }
        return null;
    }
}
