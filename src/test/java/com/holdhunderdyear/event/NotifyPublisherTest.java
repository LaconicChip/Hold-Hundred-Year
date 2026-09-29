package com.holdhunderdyear.event;

import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 提醒消息落库惯例（纯单测 + mock mapper）：type=NOTIFY、未读、当日、created_at 非空，
 * source 按入口分 SYSTEM / NPC，stockId 缺省为空。
 */
class NotifyPublisherTest {

    @Test
    void systemNoticeUsesTheSharedFieldConvention() {
        UserMessageMapper mapper = mock(UserMessageMapper.class);
        new NotifyPublisher(mapper).system(7L, 3, "追保通知", "维持担保比例已低于 140%");

        UserMessage saved = capture(mapper);
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
        UserMessageMapper mapper = mock(UserMessageMapper.class);
        new NotifyPublisher(mapper).npc(9L, 12, "张大妈荐股", "她看好这只票", 5L);

        UserMessage saved = capture(mapper);
        assertEquals("NPC", saved.getSource(), "npc notice source drives the avatar chip");
        assertEquals(5L, saved.getStockId(), "stock id");
        assertEquals(12, saved.getGameDay(), "game day");
    }

    private static UserMessage capture(UserMessageMapper mapper) {
        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}
