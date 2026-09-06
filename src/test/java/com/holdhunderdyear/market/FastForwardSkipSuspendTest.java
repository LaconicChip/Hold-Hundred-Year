package com.holdhunderdyear.market;

import com.holdhunderdyear.ws.PushService;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;

/**
 * 快进悬挂接线测试（Task 10.2）：skip() 全程持锁推进时，先 suspendQuotes 再逐跳 tick（内部
 * pushQuotes），最后 resumeQuotes 恢复——保证快进全程行情静默。实际静默过滤由 PushServiceTest
 * 覆盖；本类断言服务端接线顺序正确。
 */
@SpringBootTest
@Transactional
class FastForwardSkipSuspendTest {

    @MockitoBean
    private PushService pushService;

    @Autowired
    private FastForwardService fastForwardService;

    @Autowired
    private WorldSeeder worldSeeder;

    @Test
    void skipSuspendsQuotesBeforeTicksAndResumesAfter() {
        long userId = worldSeeder.seedUser("ff_suspend1", "$2a$10$bcrypthash");
        fastForwardService.skip(userId);

        var order = inOrder(pushService);
        order.verify(pushService).suspendQuotes(userId);
        order.verify(pushService, atLeastOnce()).pushQuotes(anyLong(), any());
        order.verify(pushService).resumeQuotes(userId);
    }
}