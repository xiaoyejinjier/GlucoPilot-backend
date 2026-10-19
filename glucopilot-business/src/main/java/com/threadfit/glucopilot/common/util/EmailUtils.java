package com.threadfit.glucopilot.common.util;

/**
 * 邮箱工具。
 */
public final class EmailUtils {

    private EmailUtils() {
    }

    /**
     * 脱敏：保留首字符与 @ 后域名（如 a***@qq.com）；无法解析时整体遮蔽。
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return "***";
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at + 1);
        String head = local.substring(0, 1);
        return head + "***@" + domain;
    }

    /** 统一小写并去首尾空白，便于唯一索引与查询。 */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase();
    }
}
