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
}
