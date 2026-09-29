package com.holdhunderdyear.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.common.PageResult;
import com.holdhunderdyear.game.model.dto.MessageDTO;
import com.holdhunderdyear.market.MarketStateService;
import com.holdhunderdyear.market.WorldTickService;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息中心服务集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 覆盖：消息落库与筛选（source/truthStatus/unreadOnly/gameDay/分页/倒序）、
 * followup 追报关联、FUN/NPC 无三态来源、标记已读单条/全部、以及 8.1 事件工厂
 * 写入的三态消息可被本服务正确查询（PENDING → 原样返回）。
 */
@SpringBootTest
@Transactional
class MessageServiceTest {

    @Autowired
    private MessageService messageService;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private WorldTickService worldTickService;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    private static final Set<String> SOURCE_NAMES = Arrays.stream(EventSkeleton.Source.values())
            .map(Enum::name).collect(Collectors.toSet());

    // ---- 1. 消息落库与筛选 ----

    @Test
    void listReturnsAllOrderedDescAndPaged() {
        long userId = worldSeeder.seedUser("msg_list1", "$2a$10$bcrypthash");

        UserMessage m1 = insertMsg(userId, "RUMOR", "PENDING", false, 1, 1, null);
        insertMsg(userId, "AUTHORITY", "CONFIRMED", true, 5, 2, null);
        UserMessage m3 = insertMsg(userId, "FUN", null, true, 2, 3, null);
        UserMessage m4 = insertMsg(userId, "NPC", null, false, 3, 4, null);
        insertMsg(userId, "SYSTEM", null, true, 4, 5, null);
        UserMessage m6 = insertMsg(userId, "INSIDER", "PENDING", false, 5, 6, null);

        PageResult<MessageDTO> all = messageService.list(userId, null, null, false, null, null, false, 1, 20);
        assertEquals(6, all.getTotal(), "no-filter list must return all messages");
        assertEquals(6, all.getRecords().size(), "no-filter list must return 6 records");
        assertEquals(m6.getId(), all.getRecords().get(0).getId(), "newest createdAt must come first");
        assertEquals(m1.getId(), all.getRecords().get(5).getId(), "oldest createdAt must come last");

        PageResult<MessageDTO> rumor = messageService.list(userId, "RUMOR", null, false, null, null, false, 1, 20);
        assertEquals(1, rumor.getTotal(), "source=RUMOR must match exactly one");
        assertEquals("RUMOR", rumor.getRecords().get(0).getSource(), "source filter must produce RUMOR");

        PageResult<MessageDTO> unread = messageService.list(userId, null, null, true, null, null, false, 1, 20);
        assertEquals(3, unread.getTotal(), "unreadOnly must match exactly 3 unread");
        assertTrue(unread.getRecords().stream().allMatch(m -> !m.getIsRead()),
                "unreadOnly list must contain only unread");

        PageResult<MessageDTO> page2 = messageService.list(userId, null, null, false, null, null, false, 2, 2);
        assertEquals(2, page2.getRecords().size(), "page 2 size 2 must slice two records");
        assertEquals(6, page2.getTotal(), "total must stay 6 across pages");
        assertEquals(m4.getId(), page2.getRecords().get(0).getId(),
                "desc page 2 must start at third-newest (m4)");
        assertEquals(m3.getId(), page2.getRecords().get(1).getId(),
                "desc page 2 must continue at fourth-newest (m3)");

        PageResult<MessageDTO> day = messageService.list(userId, null, null, false, 5, null, false, 1, 20);
        assertEquals(2, day.getTotal(), "gameDay=5 must match two messages");
    }

    // ---- 2. followup 关联 ----

    @Test
    void listExposesFollowupRelation() {
        long userId = worldSeeder.seedUser("msg_follow1", "$2a$10$bcrypthash");

        UserMessage original = insertMsg(userId, "RUMOR", "CONFIRMED", false, 1, 1, null);
        UserMessage followup = insertMsg(userId, "RUMOR", null, false, 1, 2, original.getId());

        PageResult<MessageDTO> all = messageService.list(userId, null, null, false, null, null, false, 1, 20);
        List<MessageDTO> list = all.getRecords();
        assertEquals(2, list.size(), "both original and followup must be returned independently");

        MessageDTO followupDto = list.stream().filter(m -> m.getFollowupOf() != null).findFirst().orElseThrow();
        assertEquals(original.getId(), followupDto.getFollowupOf(),
                "followup must point at the original message id");
        MessageDTO originalDto = list.stream().filter(m -> m.getId().equals(original.getId()))
                .findFirst().orElseThrow();
        assertNull(originalDto.getFollowupOf(), "original message must have null followup_of");
        assertTrue(followup.getId().equals(followupDto.getId()), "followup message must be its own record");
    }

    // ---- 3. 来源枚举含 FUN/NPC（无三态）----

    @Test
    void funAndNpcMessagesHaveNullTruthStatus() {
        long userId = worldSeeder.seedUser("msg_fun1", "$2a$10$bcrypthash");

        insertMsg(userId, "FUN", null, false, 1, 1, null);
        insertMsg(userId, "NPC", null, false, 1, 2, null);

        PageResult<MessageDTO> all = messageService.list(userId, null, null, false, null, null, false, 1, 20);
        assertEquals(2, all.getTotal(), "FUN/NPC messages must be returned");

        for (MessageDTO m : all.getRecords()) {
            assertNull(m.getTruthStatus(), "FUN/NPC must have null truth_status");
            assertTrue(SOURCE_NAMES.contains(m.getSource()),
                    "source must be a valid Source enum name, got " + m.getSource());
        }
    }

    // ---- 4. 标记已读-单条 ----

    @Test
    void markReadFlipsOnlyThatMessage() {
        long userId = worldSeeder.seedUser("msg_read1", "$2a$10$bcrypthash");

        UserMessage unreadA = insertMsg(userId, "FUN", null, false, 1, 1, null);
        UserMessage unreadB = insertMsg(userId, "NPC", null, false, 1, 2, null);

        messageService.markRead(userId, unreadA.getId());

        assertEquals(Boolean.TRUE, byId(unreadA.getId()).getIsRead(),
                "marked message must become read");
        assertEquals(Boolean.FALSE, byId(unreadB.getId()).getIsRead(),
                "other messages must stay unread");
    }

    @Test
    void markReadOfMissingOrForeignMessageThrows() {
        long userId = worldSeeder.seedUser("msg_read2", "$2a$10$bcrypthash");
        long otherUserId = worldSeeder.seedUser("msg_read_other", "$2a$10$bcrypthash");

        BizException missing = assertThrows(BizException.class,
                () -> messageService.markRead(userId, 999999L),
                "marking a nonexistent message must throw");
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode(), "missing message yields NOT_FOUND");

        UserMessage otherMsg = insertMsg(otherUserId, "FUN", null, false, 1, 1, null);
        BizException foreign = assertThrows(BizException.class,
                () -> messageService.markRead(userId, otherMsg.getId()),
                "marking another user's message must throw");
        assertEquals(ErrorCode.NOT_FOUND, foreign.getErrorCode(), "foreign message yields NOT_FOUND");
    }

    // ---- 5. 标记已读-全部 ----

    @Test
    void markAllReadFlipsEveryMessageOfTheUser() {
        long userId = worldSeeder.seedUser("msg_readall1", "$2a$10$bcrypthash");

        UserMessage a = insertMsg(userId, "FUN", null, false, 1, 1, null);
        UserMessage b = insertMsg(userId, "RUMOR", "CONFIRMED", false, 1, 2, null);
        UserMessage c = insertMsg(userId, "NPC", null, false, 1, 3, null);
        long otherUserId = worldSeeder.seedUser("msg_readall_other", "$2a$10$bcrypthash");
        UserMessage other = insertMsg(otherUserId, "FUN", null, false, 1, 4, null);

        messageService.markAllRead(userId);

        assertEquals(Boolean.TRUE, byId(a.getId()).getIsRead(), "a must be read");
        assertEquals(Boolean.TRUE, byId(b.getId()).getIsRead(), "b must be read");
        assertEquals(Boolean.TRUE, byId(c.getId()).getIsRead(), "c must be read");
        assertEquals(Boolean.FALSE, byId(other.getId()).getIsRead(),
                "another user's message must stay untouched");
    }

    // ---- 6. 三态完整性（与 8.1 打通）----

    @Test
    void eventFactoryPendingMessagesAreQueriedByService() {
        long userId = worldSeeder.seedUser("msg_tri1", "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(1618033L);
        userGameStateMapper.updateById(gs);
        marketStateService.startDay(userId);

        UserMessage pending = null;
        int ticks = 0;
        while (pending == null && ticks < 400) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
            ticks++;
            List<UserMessage> msgs = userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                    .eq(UserMessage::getUserId, userId)
                    .eq(UserMessage::getType, "EVENT"));
            pending = msgs.stream().filter(m -> "PENDING".equals(m.getTruthStatus()))
                    .findFirst().orElse(null);
        }
        assertNotNull(pending, "a PENDING event message must appear within 400 ticks");
        UserMessage found = pending;

        assertEquals("PENDING", found.getTruthStatus(),
                "8.1 event factory must persist PENDING for media/rumor/insider");
        assertTrue(Set.of("MEDIA", "RUMOR", "INSIDER").contains(found.getSource()),
                "pending sources must be media/rumor/insider");

        PageResult<MessageDTO> queried = messageService.list(userId,
                found.getSource(), null, false, null, null, false, 1, 20);
        MessageDTO dto = queried.getRecords().stream()
                .filter(m -> m.getId().equals(found.getId())).findFirst().orElseThrow();
        assertEquals("PENDING", dto.getTruthStatus(),
                "MessageService must return the same pending truth_status the factory wrote");
    }

    @Test
    void listCanBeFilteredByStock() {
        long userId = worldSeeder.seedUser("msg_stock1", "$2a$10$bcrypthash");
        UserMessage onStock = insertMsg(userId, "MEDIA", null, false, 1, 10, null);
        onStock.setStockId(8L);
        userMessageMapper.updateById(onStock);
        insertMsg(userId, "AUTHORITY", null, false, 1, 20, null); // 全市场消息：不带 stockId

        PageResult<MessageDTO> onlyStock = messageService.list(userId, null, null, false, null, 8L, false, 1, 20);

        assertEquals(1, onlyStock.getTotal(), "only the stock-tagged message must come back");
        assertEquals(onStock.getId(), onlyStock.getRecords().get(0).getId());
        assertEquals(2, messageService.list(userId, null, null, false, null, null, false, 1, 20).getTotal(),
                "without the stockId filter both messages stay visible");
    }

    @Test
    void includeSectorAddsSectorWideNewsToTheStockView() {
        long userId = worldSeeder.seedUser("msg_sector1", "$2a$10$bcrypthash");
        UserMessage onStock = insertMsg(userId, "MEDIA", null, false, 1, 10, null);
        onStock.setStockId(15L); // 天算算力，属"算力"板块
        userMessageMapper.updateById(onStock);
        UserMessage sameSector = insertMsg(userId, "AUTHORITY", null, false, 1, 20, null);
        sameSector.setSector("算力");
        userMessageMapper.updateById(sameSector);
        UserMessage otherSector = insertMsg(userId, "AUTHORITY", null, false, 1, 30, null);
        otherSector.setSector("白酒");
        userMessageMapper.updateById(otherSector);

        PageResult<MessageDTO> strict = messageService.list(userId, null, null, false, null, 15L, false, 1, 20);
        assertEquals(1, strict.getTotal(), "strict stockId filter must ignore sector-wide news");

        PageResult<MessageDTO> related = messageService.list(userId, null, null, false, null, 15L, true, 1, 20);
        assertEquals(2, related.getTotal(), "includeSector must pull in this stock's sector news");
        assertTrue(related.getRecords().stream().anyMatch((dto) -> "算力".equals(dto.getSector())),
                "sector field must reach the DTO");
        assertTrue(related.getRecords().stream().noneMatch((dto) -> "白酒".equals(dto.getSector())),
                "other sectors' news must stay out");
    }

    // ---- 辅助 ----

    private UserMessage insertMsg(long userId, String source, String truthStatus,
                                  boolean isRead, Integer gameDay, long createdInc, Long followupOf) {
        UserMessage m = new UserMessage();
        m.setUserId(userId);
        m.setSource(source);
        m.setTruthStatus(truthStatus);
        m.setType("EVENT");
        m.setTitle("title-" + source + "-" + createdInc);
        m.setContent("content-" + createdInc);
        m.setIsRead(isRead);
        m.setGameDay(gameDay);
        m.setFollowupOf(followupOf);
        m.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(createdInc));
        userMessageMapper.insert(m);
        return m;
    }

    private UserMessage byId(long id) {
        return userMessageMapper.selectById(id);
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    /** 午休恢复下午交易；收盘后开启下一交易日。 */
    private void handleBoundary(long userId) {
        UserGameState gs = gameState(userId);
        switch (gs.getMarketState()) {
            case "LUNCH_BREAK" -> marketStateService.resumeAfternoon(userId);
            case "CLOSED" -> marketStateService.startDay(userId);
            default -> { }
        }
    }
}