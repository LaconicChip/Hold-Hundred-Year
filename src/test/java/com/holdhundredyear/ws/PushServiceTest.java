package com.holdhundredyear.ws;

import com.holdhundredyear.game.model.dto.MessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 四条 STOMP 推送通道单元测试（Task 10.2）：以 mock SimpMessagingTemplate 捕获
 * convertAndSendToUser 目标路由，验证 /queue/quotes、/queue/messages、/queue/notifications、
 * /queue/danmu 各通道的 receiver=userId 与 destination，以及在线/快进悬挂过滤。
 */
class PushServiceTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final WsSessionRegistry registry = new WsSessionRegistry();
    private final PushService push = new PushService(template, registry);

    @Test
    void pushQuotesRoutesToUserQueueWhenOnline() {
        registry.register(7L, "s1");
        Object snapshot = java.util.List.of(1, 2, 3);
        push.pushQuotes(7L, snapshot);
        verify(template).convertAndSendToUser("7", "/queue/quotes", snapshot);
    }

    @Test
    void offlineUserWithNoActiveSessionGetsNoQuotes() {
        push.pushQuotes(7L, "snap");
        verifyNoInteractions(template);
    }

    @Test
    void suspendedQuotesAreSilentAndResumeRestoresDelivery() {
        registry.register(7L, "s1");
        push.suspendQuotes(7L);
        push.pushQuotes(7L, "snap");
        verifyNoInteractions(template);

        push.resumeQuotes(7L);
        push.pushQuotes(7L, "snap");
        verify(template).convertAndSendToUser("7", "/queue/quotes", "snap");
    }

    @Test
    void messageNotificationDanmuRouteToTheirOwnQueues() {
        registry.register(7L, "s1");

        MessageDTO msg = new MessageDTO();
        msg.setId(1L);
        msg.setTitle("纪元切换：牛市启动");
        push.pushMessage(7L, msg);
        verify(template).convertAndSendToUser("7", "/queue/messages", msg);

        push.pushNotification(7L, "notice");
        verify(template).convertAndSendToUser("7", "/queue/notifications", "notice");

        push.pushDanmu(7L, "hello");
        verify(template).convertAndSendToUser("7", "/queue/danmu", "hello");
    }
}