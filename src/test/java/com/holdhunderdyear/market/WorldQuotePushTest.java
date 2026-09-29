package com.holdhunderdyear.market;

import com.holdhunderdyear.ws.PushService;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 每跳行情推送接入测试（Task 10.2）：在线用户推进跳数后 pushQuotes 被调用（mock PushService
 * 捕获调用；用户不注册在线，调度器仅遍历在线用户故不干扰）。推送内容的过滤（在线/悬挂）由
 * PushServiceTest 单元覆盖，本类只断言 tick 链路已把快照交给 PushService。
 */
@SpringBootTest
@Transactional
class WorldQuotePushTest {

    @MockitoBean
    private PushService pushService;

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private WorldTickService worldTickService;

    @Test
    void eachAdvancedTickDeliversAQuoteSnapshotToPushService() {
        long userId = worldSeeder.seedUser("world_push1", "$2a$10$bcrypthash");
        marketStateService.startDay(userId);

        for (int i = 0; i < 5; i++) {
            worldTickService.tickOne(userId);
        }
        verify(pushService, atLeastOnce()).pushQuotes(eq(userId), any());
    }
}