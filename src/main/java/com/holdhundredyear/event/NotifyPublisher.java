package com.holdhundredyear.event;

import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.market.QuoteBoardRegistry;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 提醒类消息落库的唯一入口（spec §7.5/§7.7）：纪元切换、结算播报、除权除息、追保强平、
 * 打新中签、剧本收官、称号解锁、NPC 角色线——这些都不是"行情事件"，只是要告诉玩家一句话，
 * 字段惯例完全一样（type=NOTIFY、未读、当日、created_at 取当前），过去在 7 个服务里各抄了一份。
 *
 * <p>重要性只分两档出口：{@link #system}/{@link #npc} 落 type=NOTIFY（飘过即走的提醒），
 * {@link #alert} 落 type=ALERT（钱已经出事、或马上要出事：追保、强平、爆仓、高利贷到期强卖
 * 与逾期、停牌潮）。前端据 type 决定呈现强度——ALERT 模态弹窗并暂停行情，NOTIFY 走大弹幕/
 * 右下角/消息中心。分级口径放在服务端，前端不再自己按标题猜（历史上前端抄过一份关键词表，
 * 后端改了标题它也不会有反应）。
 *
 * <p>行情事件（EVENT / 三态传闻）由 {@code EventFactory} 与 {@code MainScriptService} 自己组装，
 * 它们要多写 sector、truth_status、followup_of，但落库+推送同样收口在 MessageService.publish。
 */
@Component
public class NotifyPublisher {

    private final MessageService messageService;
    private final QuoteBoardRegistry quoteBoardRegistry;

    public NotifyPublisher(MessageService messageService, QuoteBoardRegistry quoteBoardRegistry) {
        this.messageService = messageService;
        this.quoteBoardRegistry = quoteBoardRegistry;
    }

    /** 系统通知（source=SYSTEM）：不绑定个股。 */
    public void system(long userId, int gameDay, String title, String content) {
        system(userId, gameDay, title, content, null);
    }

    /** 系统通知（source=SYSTEM），stockId 可为空。 */
    public void system(long userId, int gameDay, String title, String content, Long stockId) {
        publish(userId, gameDay, "SYSTEM", title, content, stockId);
    }

    /**
     * 最高一档提醒（source=SYSTEM，type=ALERT）：玩家必须立刻知道并做出决定的账户级消息。
     * 前端会弹模态窗并暂停本账户行情，关掉窗口才继续（spec §12.1 消息呈现分级）。
     */
    public void alert(long userId, int gameDay, String title, String content, Long stockId) {
        publish(userId, gameDay, "SYSTEM", "ALERT", title, content, stockId);
        // 暂停在这里由服务端自己按下（2026-10-25）。以前只靠前端弹窗时发的一次
        // POST /api/market/pause：那次请求只要丢了/超时了，玩家就会看到"要紧事"盖在屏幕上、
        // 后面的行情照跑（这正是玩家报的 bug）。暂停是 ALERT 语义的一部分，不能挂在浏览器身上。
        // 只动已缓存的行情板：没缓存说明这个世界当前没有在推进，等玩家回来由布局页 resume 兜底。
        QuoteBoard board = quoteBoardRegistry.peek(userId);
        if (board != null) {
            board.setPaused(true);
        }
    }

    /** 营业部 NPC 台词（source=NPC），前端据此配头像与蓝标。 */
    public void npc(long userId, int gameDay, String title, String content, Long stockId) {
        publish(userId, gameDay, "NPC", title, content, stockId);
    }

    private void publish(long userId, int gameDay, String source, String title, String content, Long stockId) {
        publish(userId, gameDay, source, "NOTIFY", title, content, stockId);
    }

    /** 落库 + 推送的唯一出口（type 决定前端的呈现强度，见类注释）。 */
    private void publish(long userId, int gameDay, String source, String type,
                         String title, String content, Long stockId) {
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource(source);
        message.setType(type);
        message.setTitle(title);
        message.setContent(content);
        message.setStockId(stockId);
        message.setIsRead(false);
        message.setGameDay(gameDay);
        message.setCreatedAt(LocalDateTime.now());
        messageService.publish(message);
    }
}
