package com.threadfit.glucopilot.common.exception;

import com.threadfit.glucopilot.common.api.ErrorCode;
import lombok.Getter;

/**
 * 可预期的业务异常，由 GlobalExceptionHandler 转成统一响应。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object data;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }
}
