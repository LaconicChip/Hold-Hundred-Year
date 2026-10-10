package com.holdhundredyear.ws;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 会话生命周期接线测试（spec §3 离线暂停 / §9）：STOMP CONNECT 必须把
 * （Principal 中的）userId 会话登记进 {@link WsSessionRegistry}——调度器只推进在线用户
 * 的世界；DISCONNECT 必须注销对应会话。此前无任何生产代码调用 register，用户永远
 * 离线、行情永不推进——本测试锁定该接线，防回归。
 */
class WsLifecycleListenerTest {

    private final WsSessionRegistry registry = new WsSessionRegistry();
    private final WsLifecycleListener listener = new WsLifecycleListener(registry);

    @Test
    void stompConnectRegistersTheUsersOnlineSession() {
        listener.onConnect(connectEvent(7L, "sessionA"));

        assertTrue(registry.isOnline(7L), "STOMP CONNECT must mark the user online");
        assertTrue(registry.onlineUserIds().contains(7L), "scheduler must pick up the connected user");
    }

    @Test
    void stompDisconnectRemovesOnlyThatSession() {
        listener.onConnect(connectEvent(7L, "sessionA"));
        listener.onConnect(connectEvent(7L, "sessionB"));

        listener.onDisconnect(disconnectEvent(7L, "sessionA"));
        assertTrue(registry.isOnline(7L), "user stays online while another session remains");

        listener.onDisconnect(disconnectEvent(7L, "sessionB"));
        assertFalse(registry.isOnline(7L), "last disconnect must take the user offline");
    }

    @Test
    void connectWithoutOurPrincipalIsIgnored() {
        listener.onConnect(connectEvent(0L, "anon"));
        assertFalse(registry.isOnline(0L), "a session without SimplePrincipal must not be registered");
    }

    private SessionConnectEvent connectEvent(long userId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        if (userId > 0) {
            accessor.setUser(new SimplePrincipal(userId));
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(this, message, accessor.getUser());
    }

    private SessionDisconnectEvent disconnectEvent(long userId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        if (userId > 0) {
            accessor.setUser(new SimplePrincipal(userId));
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, sessionId, CloseStatus.NORMAL, accessor.getUser());
    }
}
