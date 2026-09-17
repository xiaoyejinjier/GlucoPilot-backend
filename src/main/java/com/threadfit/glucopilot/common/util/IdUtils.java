package com.threadfit.glucopilot.common.util;

import java.util.UUID;

/**
 * ID 工具：请求追踪等场景使用 UUID；业务主键仍用库自增。
 */
public final class IdUtils {

    private IdUtils() {
    }

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
