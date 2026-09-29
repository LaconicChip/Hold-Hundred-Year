package com.holdhunderdyear.web;

import com.holdhunderdyear.common.PageResult;
import com.holdhunderdyear.common.R;
import com.holdhunderdyear.event.MessageService;
import com.holdhunderdyear.game.model.dto.MessageDTO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息中心接口：查询消息（source/truthStatus/unreadOnly/gameDay 分页）、标记单条/
 * 全部已读。业务拒绝（来源枚举非法、消息不存在或不属当前用户）经 BizException 统一
 * 转为 400 + R.fail(ErrorCode)。
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    public R<PageResult<MessageDTO>> list(@AuthenticationPrincipal Long userId,
                                          @RequestParam(required = false) String source,
                                          @RequestParam(required = false) String truthStatus,
                                          @RequestParam(required = false) Boolean unreadOnly,
                                          @RequestParam(required = false) Integer gameDay,
                                          @RequestParam(required = false) Long stockId,
                                          @RequestParam(defaultValue = "false") boolean includeSector,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return R.ok(messageService.list(userId, source, truthStatus,
                Boolean.TRUE.equals(unreadOnly), gameDay, stockId, includeSector, page, size));
    }

    @PutMapping("/{id}/read")
    public R<Void> markRead(@AuthenticationPrincipal Long userId, @PathVariable long id) {
        messageService.markRead(userId, id);
        return R.ok();
    }

    @PutMapping("/read-all")
    public R<Void> markAllRead(@AuthenticationPrincipal Long userId) {
        messageService.markAllRead(userId);
        return R.ok();
    }
}