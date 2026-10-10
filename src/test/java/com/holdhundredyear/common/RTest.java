package com.holdhundredyear.common;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RTest {

    @Test
    void okWithData() {
        R<Integer> r = R.ok(1);
        assertEquals(0, r.getCode());
        assertEquals(1, r.getData().intValue());
        assertNotNull(r.getMsg());
    }

    @Test
    void okWithoutData() {
        R<Void> r = R.ok();
        assertEquals(0, r.getCode());
        assertNull(r.getData());
    }

    @Test
    void failWithErrorCode() {
        R<Void> r = R.fail(ErrorCode.LIMIT_UP_CANNOT_BUY);
        assertEquals(ErrorCode.LIMIT_UP_CANNOT_BUY.getCode(), r.getCode());
        assertEquals(1005, r.getCode());
        assertEquals("涨停封死，买单排不上", r.getMsg());
    }

    @Test
    void handlerConvertsBizExceptionToFail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        BizException e = new BizException(ErrorCode.LIMIT_UP_CANNOT_BUY);
        assertEquals("涨停封死，买单排不上", e.getMessage());
        R<Void> r = handler.handleBizException(e);
        assertEquals(ErrorCode.LIMIT_UP_CANNOT_BUY.getCode(), r.getCode());
        assertEquals("涨停封死，买单排不上", r.getMsg());
    }

    @Test
    void handlerFallsBackToInternalError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        R<Void> r = handler.handleException(new RuntimeException("boom"));
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), r.getCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.getMsg(), r.getMsg());
    }

    @Test
    void everyErrorCodeHasNonBlankEmojiFreeMsg() {
        Pattern emoji = Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]");
        for (ErrorCode ec : ErrorCode.values()) {
            assertNotNull(ec.getMsg());
            assertFalse(ec.getMsg().isBlank(), ec.name() + " msg is blank");
            assertFalse(emoji.matcher(ec.getMsg()).find(), ec.name() + " msg contains emoji: " + ec.getMsg());
        }
    }
}
