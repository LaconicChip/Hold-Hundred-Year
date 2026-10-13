package com.holdhundredyear.event;

import com.holdhundredyear.market.entity.UserMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 提醒消息字段惯例（纯单测 + mock MessageService）：type=NOTIFY、未读、当日、created_at 非空，
 * source 按入口分 SYSTEM / NPC，stockId 缺省为空。
 */
class NotifyPublisherTest {

    @Test
    void systemNoticeUsesTheSharedFieldConvention() {
        MessageService messages = mock(MessageService.class);
        new NotifyPublisher(messages).system(7L, 3, "追保通知", "维持担保比例已低于 140%");

        UserMessage saved = capture(messages);
        assertEquals(7L, saved.getUserId(), "user id must be carried");
        assertEquals("SYSTEM", saved.getSource(), "system notice source");
        assertEquals("NOTIFY", saved.getType(), "notice type");
        assertEquals("追保通知", saved.getTitle(), "title");
        assertEquals("维持担保比例已低于 140%", saved.getContent(), "content");
        assertEquals(3, saved.getGameDay(), "game day");
        assertFalse(saved.getIsRead(), "notices start unread");
        assertNotNull(saved.getCreatedAt(), "created_at must be set by the publisher");
        assertNull(saved.getStockId(), "no stock binding for a market-level notice");
    }

    @Test
    void npcNoticeCarriesNpcSourceAndStock() {
        MessageService messages = mock(MessageService.class);
        new NotifyPublisher(messages).npc(9L, 12, "张大妈荐股", "她看好这只票", 5L);

        UserMessage saved = capture(messages);
        assertEquals("NPC", saved.getSource(), "npc notice source drives the avatar chip");
        assertEquals(5L, saved.getStockId(), "stock id");
        assertEquals(12, saved.getGameDay(), "game day");
    }

    /**
     * 最高一档提醒落 type=ALERT：前端按 type 决定"模态弹窗 + 暂停行情"，所以这个字段就是
     * 分级的唯一载体，不能被 system 的惯例带跑。
     */
    @Test
    void alertIsTheCriticalTier() {
        MessageService messages = mock(MessageService.class);
        new NotifyPublisher(messages).alert(7L, 12, "追保通知", "维持担保比例已低于 140%", null);

        UserMessage saved = capture(messages);
        assertEquals("ALERT", saved.getType(), "critical notices must carry the alert type");
        assertEquals("SYSTEM", saved.getSource(), "still a system message for the source chip");
        assertEquals(7L, saved.getUserId(), "user id");
        assertEquals(12, saved.getGameDay(), "game day");
        assertFalse(saved.getIsRead(), "alerts start unread");
    }

    /** 取交给 MessageService.publish 的那条消息（publish 返回入参，所以直接透传 mock 返回值）。 */
    private static UserMessage capture(MessageService messages) {
        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(messages).publish(captor.capture());
        return captor.getValue();
    }
}
