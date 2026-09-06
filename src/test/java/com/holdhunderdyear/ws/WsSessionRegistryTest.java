package com.holdhunderdyear.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线会话注册表单元测试（Task 10.2）：验证 userId → 多会话管理的注册/注销/断连清理，
 * 以及单参数传统语义（调度器兼容）与在线判定。
 */
class WsSessionRegistryTest {

    @Test
    void userStaysOnlineUntilLastSessionDisconnects() {
        WsSessionRegistry registry = new WsSessionRegistry();
        registry.register(7L, "sessionA");
        registry.register(7L, "sessionB");
        assertTrue(registry.onlineUserIds().contains(7L), "a user with active sessions must be online");

        registry.unregister(7L, "sessionA");
        assertTrue(registry.isOnline(7L), "user stays online while at least one session remains");

        registry.onSessionDisconnected(7L, "sessionB");
        assertFalse(registry.isOnline(7L), "user goes offline when the last session disconnects");
        assertTrue(registry.onlineUserIds().isEmpty(), "online list must drop the user after the last session");
    }

    @Test
    void multipleUsersAreTrackedIndependently() {
        WsSessionRegistry registry = new WsSessionRegistry();
        registry.register(1L, "a");
        registry.register(2L, "b");
        assertEquals(List.of(1L, 2L), registry.onlineUserIds(), "online ids must be returned sorted ascending");
        registry.unregister(1L, "a");
        assertFalse(registry.isOnline(1L), "removing its only session must take user 1 offline");
        assertTrue(registry.isOnline(2L), "an unrelated user must be unaffected");
    }

    @Test
    void legacySingleArgRegisterUnregisterStillWorks() {
        WsSessionRegistry registry = new WsSessionRegistry();
        registry.register(5L);
        assertTrue(registry.isOnline(5L), "single-arg register must still mark online (scheduler compat)");
        registry.unregister(5L);
        assertFalse(registry.onlineUserIds().contains(5L), "single-arg unregister must drop the user");
    }

    @Test
    void unregisterUnknownSessionIsNoop() {
        WsSessionRegistry registry = new WsSessionRegistry();
        registry.unregister(9L, "ghost");
        registry.onSessionDisconnected(9L, "ghost");
        assertFalse(registry.isOnline(9L), "touching an unknown session must not create an online entry");
    }
}