package com.holdhunderdyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.common.PageResult;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.dto.MessageDTO;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.ws.PushService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 消息中心服务（Task 8.2，spec §11/§13）：写入（{@link #publish}）、查询（来源/真伪三态/
 * 未读/按日筛选、分页、新建先后倒序）、标记已读。truth_status 与 followup_of 原样返回，不做拼装。
 *
 * <p>全仓所有消息写入都必须走 {@link #publish}：落库后立刻把同一份 DTO 推到
 * /user/queue/messages，前端据此实时"当面提醒"，不再靠轮询发现新消息。
 */
@Service
public class MessageService {

    private final UserMessageMapper userMessageMapper;
    private final PushService pushService;

    public MessageService(UserMessageMapper userMessageMapper, PushService pushService) {
        this.userMessageMapper = userMessageMapper;
        this.pushService = pushService;
    }

    /**
     * 消息落库 + 实时推送的唯一出口：insert 完就把同一份 DTO 推给该用户的消息通道，
     * 前端按 messageMeta 的通道规则决定大弹幕/浮层/只进列表。推送是尽力而为
     * （{@link PushService} 内部吞异常），绝不因为推不动而影响消息写入。
     *
     * @return 传入的实体（已带自增 id，供 followup_of 等后续引用）
     */
    public UserMessage publish(UserMessage message) {
        userMessageMapper.insert(message);
        pushService.pushMessage(message.getUserId(), toDto(message));
        return message;
    }

    /**
     * 分页查询某用户消息。可选筛选：source（须为 Source 枚举名，否则 400）、
     * truthStatus、unreadOnly（仅未读）、gameDay、stockId（只看某只票的历史消息，个股详情页用）、
     * includeSector（配合 stockId：连同本票所属板块的板块级消息一起返回）；
     * 按 createdAt,id 倒序。page 默认 1、size 默认 20。
     */
    @Transactional(readOnly = true)
    public PageResult<MessageDTO> list(long userId, String source, String truthStatus,
                                       boolean unreadOnly, Integer gameDay, Long stockId,
                                       boolean includeSector, int page, int size) {
        if (source != null && !source.isBlank()
                && Arrays.stream(EventSkeleton.Source.values()).noneMatch(s -> s.name().equals(source))) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        LambdaQueryWrapper<UserMessage> wrapper = new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(source != null && !source.isBlank(), UserMessage::getSource, source)
                .eq(truthStatus != null && !truthStatus.isBlank(), UserMessage::getTruthStatus, truthStatus)
                .eq(unreadOnly, UserMessage::getIsRead, false)
                .eq(gameDay != null, UserMessage::getGameDay, gameDay)
                .eq(stockId != null && !includeSector, UserMessage::getStockId, stockId)
                .orderByDesc(UserMessage::getCreatedAt)
                .orderByDesc(UserMessage::getId);

        if (stockId != null && includeSector) {
            applyStockOrSector(wrapper, stockId);
        }

        List<UserMessage> all = userMessageMapper.selectList(wrapper);
        long total = all.size();

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<MessageDTO> records = all.subList(from, to).stream()
                .map(this::toDto).collect(Collectors.toList());
        return new PageResult<>(records, total);
    }

    /**
     * 个股页口径：本票专属消息（stock_id 命中）+ 本票所属板块的板块级消息
     * （sector 命中且 stock_id 为空）。板块名由后端按 StockDefs 反查，前端不必知道。
     */
    private void applyStockOrSector(LambdaQueryWrapper<UserMessage> wrapper, long stockId) {
        String sector;
        try {
            sector = StockDefs.byId(stockId).sector();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        wrapper.and(w -> w.eq(UserMessage::getStockId, stockId)
                .or(o -> o.isNull(UserMessage::getStockId).eq(UserMessage::getSector, sector)));
    }

    /** 标记该用户一条消息为已读；不存在（或不属于该用户）抛 NOT_FOUND。 */
    @Transactional
    public void markRead(long userId, long messageId) {
        UserMessage message = userMessageMapper.selectOne(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getId, messageId)
                .eq(UserMessage::getUserId, userId));
        if (message == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        message.setIsRead(true);
        userMessageMapper.updateById(message);
    }

    /** 标记该用户全部消息为已读。 */
    @Transactional
    public void markAllRead(long userId) {
        userMessageMapper.update(null, new LambdaUpdateWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .set(UserMessage::getIsRead, true));
    }

    /** 实体 → 展示 DTO：字段原样拷贝，天然保留 truth_status 与 followup_of。 */
    private MessageDTO toDto(UserMessage m) {
        MessageDTO dto = new MessageDTO();
        dto.setId(m.getId());
        dto.setUserId(m.getUserId());
        dto.setSource(m.getSource());
        dto.setTruthStatus(m.getTruthStatus());
        dto.setType(m.getType());
        dto.setTitle(m.getTitle());
        dto.setContent(m.getContent());
        dto.setStockId(m.getStockId());
        dto.setSector(m.getSector());
        dto.setFollowupOf(m.getFollowupOf());
        dto.setIsRead(m.getIsRead());
        dto.setGameDay(m.getGameDay());
        dto.setCreatedAt(m.getCreatedAt());
        return dto;
    }
}