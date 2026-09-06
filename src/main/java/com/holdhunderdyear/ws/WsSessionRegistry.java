package com.holdhunderdyear.ws;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线用户注册表：记录当前在线 userId 集合，供调度器（MarketScheduler）每轮遍历推进各自
 * 世界（Phase 8.3 既有语义）。Task 10.2 增量扩展为会话级管理（userId → sessionId 集合，
 * 断连清理），单参数与双参数注册互通：任一会话在线即视为该 userId 在线。
 *
 * <p>并发协议：ConcurrentHashMap.newKeySet 承载，注册/注销/快照均无锁；快照为不可变
 * 有序列表，遍历期间后续注册/注销不影响本轮（下一轮快照才可见）。
 */
@Service
public class WsSessionRegistry {

    /** 在线 userId 集合（在线 = 至少一个活跃会话；或单参数 register 显式标记，调度器兼容）。 */
    private final Set<Long> onlineUsers = ConcurrentHashMap.newKeySet();

    /** userId → 其活跃会话 id 集合（Task 10.2）。 */
    private final Map<Long, Set<String>> userSessions = new ConcurrentHashMap<>();

    /** 标记用户在线（重复注册幂等；Phase 8.3 调度器兼容路径）。 */
    public void register(long userId) {
        onlineUsers.add(userId);
    }

    /** 标记用户离线（未注册时无副作用；Phase 8.3 调度器兼容路径）。 */
    public void unregister(long userId) {
        onlineUsers.remove(userId);
    }

    /** 注册一个 WebSocket 会话（Task 10.2）：把 sessionId 归入该 userId 的会话集合，并使其
     *  进入在线行列（调度器据此推进其世界）。重复会话幂等。 */
    public void register(long userId, String sessionId) {
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        onlineUsers.add(userId);
    }

    /** 注销一个会话（Task 10.2）：移除该 sessionId；该 userId 无剩余会话时移除其会话集合
     *  与在线标记。未知会话/未知用户无副作用。 */
    public void unregister(long userId, String sessionId) {
        Set<String> sessions = userSessions.get(userId);
        if (sessions == null) {
            return;
        }
        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            userSessions.remove(userId);
            onlineUsers.remove(userId);
        }
    }

    /** 断连清理（WebSocket disconnect 生命周期调用）：与 {@link #unregister(long, String)}
     *  语义一致，供客户端断线时一次性收尾。 */
    public void onSessionDisconnected(long userId, String sessionId) {
        unregister(userId, sessionId);
    }

    /** 用户是否在线（至少一个活跃会话；或单参数 register 显式标记）。 */
    public boolean isOnline(long userId) {
        return onlineUsers.contains(userId);
    }

    /** 当前在线用户 id 快照（不可变，按 userId 升序）。 */
    public List<Long> onlineUserIds() {
        return onlineUsers.stream().sorted().toList();
    }
}
