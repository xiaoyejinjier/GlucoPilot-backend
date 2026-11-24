package com.threadfit.glucopilot.common.util;

/**
 * 手机号工具。
 */
public final class PhoneUtils {

    private PhoneUtils() {
    }

    /**
     * 脱敏：保留前 3 后 4（如 138****0000）；null 或长度不足时整体遮蔽。
     */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 用户名脱敏：保留首字（如「张**」）；null / 空白时整体遮蔽。
     */
    public static String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "***";
        }
        return username.charAt(0) + "**";
    }
}
