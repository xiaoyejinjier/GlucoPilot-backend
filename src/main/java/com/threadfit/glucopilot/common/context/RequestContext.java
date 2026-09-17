package com.threadfit.glucopilot.common.context;

/**
 * 请求级上下文（ThreadLocal）。
 * 由 Filter / Security 写入，业务只读；请求结束必须 clear，避免线程池串数据。
 */
public final class RequestContext {

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setRequestId(String requestId) {
        current().requestId = requestId;
    }

    public static String getRequestId() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.requestId;
    }

    public static void setUserId(Long userId) {
        current().userId = userId;
    }

    public static Long getUserId() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.userId;
    }

    /** C 端当前用户；未登录抛 40100。 */
    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new com.threadfit.glucopilot.common.exception.BizException(
                    com.threadfit.glucopilot.common.api.ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    public static void setOpsId(Long opsId) {
        current().opsId = opsId;
    }

    public static Long getOpsId() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.opsId;
    }

    public static Long requireOpsId() {
        Long opsId = getOpsId();
        if (opsId == null) {
            throw new IllegalStateException("current opsId is missing");
        }
        return opsId;
    }

    public static void setClientIp(String clientIp) {
        current().clientIp = clientIp;
    }

    public static String getClientIp() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.clientIp;
    }

    public static void setUserAgent(String userAgent) {
        current().userAgent = userAgent;
    }

    public static String getUserAgent() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.userAgent;
    }

    public static void setIdempotencyKey(String idempotencyKey) {
        current().idempotencyKey = idempotencyKey;
    }

    public static String getIdempotencyKey() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.idempotencyKey;
    }

    /**
     * 写审计字段时的操作人：优先 ops，其次 user，否则 0。
     */
    public static long operatorIdOrZero() {
        Long opsId = getOpsId();
        if (opsId != null) {
            return opsId;
        }
        Long userId = getUserId();
        return userId == null ? 0L : userId;
    }

    public static void clear() {
        HOLDER.remove();
    }

    private static Holder current() {
        Holder holder = HOLDER.get();
        if (holder == null) {
            holder = new Holder();
            HOLDER.set(holder);
        }
        return holder;
    }

    private static final class Holder {
        private String requestId;
        private Long userId;
        private Long opsId;
        private String clientIp;
        private String userAgent;
        private String idempotencyKey;
    }
}
