package com.threadfit.glucopilot.common.util;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;

/**
 * 简洁断言：失败直接抛 BizException，减少 Service 里重复 if。
 */
public final class Asserts {

    private Asserts() {
    }

    public static void isTrue(boolean expression, ErrorCode errorCode) {
        if (!expression) {
            throw new BizException(errorCode);
        }
    }

    public static void isTrue(boolean expression, ErrorCode errorCode, String message) {
        if (!expression) {
            throw new BizException(errorCode, message);
        }
    }

    public static void notNull(Object value, ErrorCode errorCode) {
        if (value == null) {
            throw new BizException(errorCode);
        }
    }

    public static void notNull(Object value, ErrorCode errorCode, String message) {
        if (value == null) {
            throw new BizException(errorCode, message);
        }
    }

    public static void notBlank(String value, ErrorCode errorCode, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(errorCode, message);
        }
    }
}
