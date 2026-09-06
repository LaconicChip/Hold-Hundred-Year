package com.holdhunderdyear.ws;

import com.holdhunderdyear.market.entity.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时推送服务（Phase 8.3 占位 → Task 10.2 完整四通道）：基于 STOMP per-user 队列
 * ({@code /user/{userId}/queue/*}) 下发行情/消息/通知/弹幕。destination 皆为
 * {@code /user/queue/{通道}}，经 {@link SimpMessagingTemplate#convertAndSendToUser} 投递。
 *
 * <p>过滤与悬挂：行情通道仅在用户在线且未处于快进悬挂({@link #suspendQuotes})时下发；
 * 消息/通知/弹幕一般为尽力而为，不做过您在过滤（前端决定是否展示）。
 * 全部推送均为尽力而为——失败仅记日志、绝不影响主流程（spec §13）。
 */
@Slf4j
@Service
public class PushService {

    private final SimpMessagingTemplate messagingTemplate;
    private final WsSessionRegistry wsSessionRegistry;

    /** 快进悬挂中的 userId 集合：期间行情推送静默（进程内标记，Task 10.2）。 */
    private final Set<Long> suspended = ConcurrentHashMap.newKeySet();

    public PushService(SimpMessagingTemplate messagingTemplate, WsSessionRegistry wsSessionRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.wsSessionRegistry = wsSessionRegistry;
    }

    /** 下发每跳行情快照（仅当用户在线且未悬挂）。 */
    public void pushQuotes(long userId, Object snapshot) {
        if (suspended.contains(userId) || !wsSessionRegistry.isOnline(userId)) {
            return;
        }
        sendToUser(userId, "/queue/quotes", snapshot);
    }

    /** 下发一条消息 DTO 给用户的消息中心。 */
    public void pushMessage(long userId, UserMessage message) {
        sendToUser(userId, "/queue/messages", message);
    }

    /** 下发一条通知（轻量施展对象，前端据此弹提示）。 */
    public void pushNotification(long userId, Object notification) {
        sendToUser(userId, "/queue/notifications", notification);
    }

    /** 下发一条弹幕文案（沿用 Phase 8.3 已接的 DanmuService 调用路径，改走真实模板下发）。 */
    public void pushDanmu(long userId, String danmu) {
        sendToUser(userId, "/queue/danmu", danmu);
    }

    /** 悬挂某用户的行情推送（快进期间静默）。 */
    public void suspendQuotes(long userId) {
        suspended.add(userId);
    }

    /** 恢复某用户的行情推送（快进结束恢复）。 */
    public void resumeQuotes(long userId) {
        suspended.remove(userId);
    }

    /** 尽力而为投递：任何失败仅记 warn，不打断调用方主流程。 */
    private void sendToUser(long userId, String destination, Object payload) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), destination, payload);
        } catch (RuntimeException e) {
            log.warn("ws push failed: userId={}, destination={}, reason={}", userId, destination, e.getMessage());
        }
    }
}