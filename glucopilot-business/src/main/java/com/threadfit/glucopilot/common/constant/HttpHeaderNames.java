package com.threadfit.glucopilot.common.constant;

/**
 * 公共 HTTP 头名称。
 */
public final class HttpHeaderNames {

    public static final String AUTHORIZATION = "Authorization";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY = "X-Idempotency-Key";

    private HttpHeaderNames() {
    }
}
