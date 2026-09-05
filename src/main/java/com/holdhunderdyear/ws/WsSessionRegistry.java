package com.holdhunderdyear.ws;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最小在线用户注册表：记录当前在线的 userId 集合，供调度器（MarketScheduler）每轮
 * 遍历推进各自世界。注册/注销由 WebSocket 连接生命周期调用，Phase 10 接入时将扩展
 * 为会话级管理（userId → session，断连清理）；当前仅支撑调度器遍历这一件事。
 *
 * <p>并发协议：ConcurrentHashMap.newKeySet 承载，注册/注销/快照均无锁；快照为不可变
 * 有序列表，遍历期间后续注册/注销不影响本轮（下一轮快照才可见）。
 */
@Service
public class WsSessionRegistry {

    private final Set<Long> onlineUsers = ConcurrentHashMap.newKeySet();

    /** 标记用户在线（重复注册幂等）。 */
    public void register(long userId) {
        onlineUsers.add(userId);
    }

    /** 标记用户离线（未注册时无副作用）。 */
    public void unregister(long userId) {
        onlineUsers.remove(userId);
    }

    /** 当前在线用户 id 快照（不可变，按 userId 升序）。 */
    public List<Long> onlineUserIds() {
        return onlineUsers.stream().sorted().toList();
    }
}
