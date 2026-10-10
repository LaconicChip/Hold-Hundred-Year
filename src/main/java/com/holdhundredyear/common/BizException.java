package com.holdhundredyear.common;

import lombok.Getter;

/**
 * 业务异常，携带 ErrorCode，message 取错误码文案。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }
}
