package com.threadfit.glucopilot.device.adapter.support;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

public final class DeviceProviders {

    public static final String MOCK = "MOCK";
    public static final String HUAWEI = "HUAWEI";
    public static final String HEALTH_CONNECT = "HEALTH_CONNECT";
    public static final String APPLE_HEALTH = "APPLE_HEALTH";
    public static final String NIGHTSCOUT = "NIGHTSCOUT";

    public static final Set<String> ALL = Set.of(
            MOCK, HUAWEI, HEALTH_CONNECT, APPLE_HEALTH, NIGHTSCOUT);

    private DeviceProviders() {
    }

    public static String normalize(String provider) {
        if (!StringUtils.hasText(provider)) {
            return MOCK;
        }
        String code = provider.trim().toUpperCase(Locale.ROOT);
        if (!ALL.contains(code)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的设备厂商: " + provider);
        }
        return code;
    }

    public static boolean isSupported(String provider) {
        return StringUtils.hasText(provider) && ALL.contains(provider.trim().toUpperCase(Locale.ROOT));
    }
}
