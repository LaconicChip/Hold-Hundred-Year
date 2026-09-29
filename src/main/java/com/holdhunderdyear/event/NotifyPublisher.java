package com.holdhunderdyear.event;

import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 提醒类消息落库的唯一入口（spec §7.5/§7.7）：纪元切换、结算播报、除权除息、追保强平、
 * 打新中签、剧本收官、称号解锁、NPC 角色线——这些都不是"行情事件"，只是要告诉玩家一句话，
 * 字段惯例完全一样（type=NOTIFY、未读、当日、created_at 取当前），过去在 7 个服务里各抄了一份。
 *
 * <p>行情事件（EVENT / 三态传闻）仍走 {@code EventFactory} 与 {@code MainScriptService}，
 * 它们要写 sector、truth_status、followup_of，语义不同，不并进来。
 */
@Component
public class NotifyPublisher {

    private final UserMessageMapper userMessageMapper;

    public NotifyPublisher(UserMessageMapper userMessageMapper) {
        this.userMessageMapper = userMessageMapper;
    }

    /** 系统通知（source=SYSTEM）：不绑定个股。 */
    public void system(long userId, int gameDay, String title, String content) {
        system(userId, gameDay, title, content, null);
    }

    /** 系统通知（source=SYSTEM），stockId 可为空。 */
    public void system(long userId, int gameDay, String title, String content, Long stockId) {
        publish(userId, gameDay, "SYSTEM", title, content, stockId);
    }

    /** 营业部 NPC 台词（source=NPC），前端据此配头像与蓝标。 */
    public void npc(long userId, int gameDay, String title, String content, Long stockId) {
        publish(userId, gameDay, "NPC", title, content, stockId);
    }

    private void publish(long userId, int gameDay, String source, String title, String content, Long stockId) {
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource(source);
        message.setType("NOTIFY");
        message.setTitle(title);
        message.setContent(content);
        message.setStockId(stockId);
        message.setIsRead(false);
        message.setGameDay(gameDay);
        message.setCreatedAt(LocalDateTime.now());
        userMessageMapper.insert(message);
    }
}
