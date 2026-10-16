package com.holdhundredyear.market;

import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 行情板注册表的兜底：没有 user_game_state 就没有这个世界，必须给业务错误码，
 * 不是在 gameState.getIndexValue() 上抛 NPE 变成 500。
 *
 * <p>{@link com.holdhundredyear.config.JwtAuthFilter} 已经把"用户不存在"的令牌挡在门外，
 * 这条是第二道闸：任何绕过鉴权面的调用方（调度器、未来的批量任务）拿到的也应该是 1018，
 * 而不是一堆没人看得懂的堆栈。
 */
@SpringBootTest
@Transactional
class QuoteBoardRegistryTest {

    @Autowired
    private QuoteBoardRegistry registry;

    @Test
    void getOrLoadWithoutGameStateThrowsNotFoundNotNpe() {
        long nobody = 900_000_001L;
        BizException e = assertThrows(BizException.class, () -> registry.getOrLoad(nobody),
                "a world with no game_state must fail as a business error");
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode(),
                "must surface NOT_FOUND so the caller can treat it as 资源不存在");
    }
}
