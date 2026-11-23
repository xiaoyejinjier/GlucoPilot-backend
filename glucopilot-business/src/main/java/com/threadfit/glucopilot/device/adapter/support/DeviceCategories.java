package com.threadfit.glucopilot.device.adapter.support;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 设备产品类型（与 {@link DeviceProviders} 适配器编码分离）。
 */
public final class DeviceCategories {

    public static final String CGM = "CGM";
    public static final String BGM = "BGM";
    public static final String PUMP = "PUMP";
    public static final String PEN = "PEN";
    public static final String BAND = "BAND";
    public static final String WATCH = "WATCH";
    public static final String RING = "RING";
    public static final String HUB = "HUB";
    public static final String SCALE = "SCALE";
    public static final String BP = "BP";
    public static final String OXIMETER = "OXIMETER";
    public static final String OTHER = "OTHER";

    public static final Set<String> ALL = Set.of(
            CGM, BGM, PUMP, PEN, BAND, WATCH, RING, HUB, SCALE, BP, OXIMETER, OTHER);

    private DeviceCategories() {
    }

    /**
     * 空或空白 → null；非法取值 → BAD_REQUEST。
     */
    public static String normalize(String category) {
        if (!StringUtils.hasText(category)) {
            return null;
        }
        String code = category.trim().toUpperCase(Locale.ROOT);
        if (!ALL.contains(code)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的设备类型: " + category);
        }
        return code;
    }
}
