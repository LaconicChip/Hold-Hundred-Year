package com.holdhunderdyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.common.PageResult;
import com.holdhunderdyear.game.model.dto.MessageDTO;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 消息中心服务（Task 8.2，spec §11）：查询用户消息（来源/真伪三态/未读/按日筛选、
 * 分页、新建先后倒序）、标记单条/全部已读。消息本身由 8.1 事件工厂写入；
 * 本服务只读查询与展示，truth_status 与 followup_of 原样返回，不做拼装。
 */
@Service
public class MessageService {

    private final UserMessageMapper userMessageMapper;

    public MessageService(UserMessageMapper userMessageMapper) {
        this.userMessageMapper = userMessageMapper;
    }

    /**
     * 分页查询某用户消息。可选筛选：source（须为 Source 枚举名，否则 400）、
     * truthStatus、unreadOnly（仅未读）、gameDay；按 createdAt,id 倒序。page 默认 1、size 默认 20。
     */
    @Transactional(readOnly = true)
    public PageResult<MessageDTO> list(long userId, String source, String truthStatus,
                                       boolean unreadOnly, Integer gameDay, int page, int size) {
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
                .orderByDesc(UserMessage::getCreatedAt)
                .orderByDesc(UserMessage::getId);

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
        dto.setFollowupOf(m.getFollowupOf());
        dto.setIsRead(m.getIsRead());
        dto.setGameDay(m.getGameDay());
        dto.setCreatedAt(m.getCreatedAt());
        return dto;
    }
}