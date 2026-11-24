package com.threadfit.glucopilot.device.adapter.support;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 设备类型启发式建议：仅在调用方未显式传入 category 时使用。
 * 匹配顺序：SN 前缀 → 名称/型号关键词；无匹配返回 null。
 */
public final class DeviceCategoryHints {

    private record SnPrefix(String prefix, String category) {
    }

    private record Keyword(String needle, String category) {
    }

    private static final List<SnPrefix> SN_PREFIXES = List.of(
            new SnPrefix("LIBRE", DeviceCategories.CGM),
            new SnPrefix("CGM", DeviceCategories.CGM),
            new SnPrefix("DEXCOM", DeviceCategories.CGM),
            new SnPrefix("GUARDIAN", DeviceCategories.CGM),
            new SnPrefix("BGM", DeviceCategories.BGM),
            new SnPrefix("ACCUS", DeviceCategories.BGM),
            new SnPrefix("ONETOUCH", DeviceCategories.BGM),
            new SnPrefix("PUMP", DeviceCategories.PUMP),
            new SnPrefix("OMNIPOD", DeviceCategories.PUMP),
            new SnPrefix("PEN", DeviceCategories.PEN),
            new SnPrefix("BAND", DeviceCategories.BAND),
            new SnPrefix("WATCH", DeviceCategories.WATCH),
            new SnPrefix("RING", DeviceCategories.RING),
            new SnPrefix("HUB", DeviceCategories.HUB),
            new SnPrefix("SCALE", DeviceCategories.SCALE),
            new SnPrefix("BP", DeviceCategories.BP),
            new SnPrefix("SPO2", DeviceCategories.OXIMETER),
            new SnPrefix("OXIM", DeviceCategories.OXIMETER)
    );

    private static final List<Keyword> KEYWORDS = List.of(
            new Keyword("LIBRE", DeviceCategories.CGM),
            new Keyword("CGM", DeviceCategories.CGM),
            new Keyword("连续血糖", DeviceCategories.CGM),
            new Keyword("动态血糖", DeviceCategories.CGM),
            new Keyword("DEXCOM", DeviceCategories.CGM),
            new Keyword("BGM", DeviceCategories.BGM),
            new Keyword("血糖仪", DeviceCategories.BGM),
            new Keyword("指尖", DeviceCategories.BGM),
            new Keyword("胰岛素泵", DeviceCategories.PUMP),
            new Keyword("PUMP", DeviceCategories.PUMP),
            new Keyword("胰岛素笔", DeviceCategories.PEN),
            new Keyword("手环", DeviceCategories.BAND),
            new Keyword("BAND", DeviceCategories.BAND),
            new Keyword("手表", DeviceCategories.WATCH),
            new Keyword("WATCH", DeviceCategories.WATCH),
            new Keyword("戒指", DeviceCategories.RING),
            new Keyword("RING", DeviceCategories.RING),
            new Keyword("网关", DeviceCategories.HUB),
            new Keyword("HUB", DeviceCategories.HUB),
            new Keyword("体脂秤", DeviceCategories.SCALE),
            new Keyword("体重秤", DeviceCategories.SCALE),
            new Keyword("SCALE", DeviceCategories.SCALE),
            new Keyword("血压", DeviceCategories.BP),
            new Keyword("血氧", DeviceCategories.OXIMETER),
            new Keyword("OXIMETER", DeviceCategories.OXIMETER)
    );

    private DeviceCategoryHints() {
    }

    /**
     * @return 闭集类型码，或 null（无建议）
     */
    public static String suggest(String deviceSn, String deviceName, String deviceModel) {
        String fromSn = matchSnPrefix(deviceSn);
        if (fromSn != null) {
            return fromSn;
        }
        String haystack = join(deviceName, deviceModel);
        if (!StringUtils.hasText(haystack)) {
            return null;
        }
        String upper = haystack.toUpperCase(Locale.ROOT);
        for (Keyword rule : KEYWORDS) {
            if (upper.contains(rule.needle().toUpperCase(Locale.ROOT))) {
                return rule.category();
            }
        }
        return null;
    }

    /** 空 category 时用建议值，否则保留已规范化的显式值。 */
    public static String resolveOrSuggest(String normalizedCategory, String deviceSn, String deviceName, String deviceModel) {
        if (StringUtils.hasText(normalizedCategory)) {
            return normalizedCategory;
        }
        return suggest(deviceSn, deviceName, deviceModel);
    }

    private static String matchSnPrefix(String deviceSn) {
        if (!StringUtils.hasText(deviceSn)) {
            return null;
        }
        String sn = deviceSn.trim().toUpperCase(Locale.ROOT);
        for (SnPrefix rule : SN_PREFIXES) {
            if (sn.startsWith(rule.prefix())) {
                return rule.category();
            }
        }
        return null;
    }

    private static String join(String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(a)) {
            sb.append(a.trim());
        }
        if (StringUtils.hasText(b)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(b.trim());
        }
        return sb.toString();
    }
}
