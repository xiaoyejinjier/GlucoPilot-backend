package com.threadfit.glucopilot.device.adapter.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeviceCategoryHintsTest {

    @Test
    void suggestBySnPrefix() {
        assertEquals(DeviceCategories.CGM, DeviceCategoryHints.suggest("LibreABC123", null, null));
        assertEquals(DeviceCategories.BGM, DeviceCategoryHints.suggest("BGM-001", null, null));
        assertEquals(DeviceCategories.WATCH, DeviceCategoryHints.suggest("WATCH9X", null, null));
    }

    @Test
    void suggestByNameKeyword() {
        assertEquals(DeviceCategories.CGM, DeviceCategoryHints.suggest("SN-1", "我的连续血糖仪", null));
        assertEquals(DeviceCategories.BGM, DeviceCategoryHints.suggest("SN-2", "家用血糖仪", null));
        assertEquals(DeviceCategories.BAND, DeviceCategoryHints.suggest("SN-3", "运动手环 Pro", null));
        assertEquals(DeviceCategories.SCALE, DeviceCategoryHints.suggest("SN-4", null, "体脂秤 X1"));
    }

    @Test
    void snPrefixBeatsKeyword() {
        assertEquals(DeviceCategories.CGM, DeviceCategoryHints.suggest("CGM999", "血压计", null));
    }

    @Test
    void noMatchReturnsNull() {
        assertNull(DeviceCategoryHints.suggest("XYZ-001", "未知设备", null));
        assertNull(DeviceCategoryHints.suggest(null, null, null));
        assertNull(DeviceCategoryHints.suggest("  ", "", ""));
    }

    @Test
    void resolveOrSuggestKeepsExplicit() {
        assertEquals(DeviceCategories.PUMP,
                DeviceCategoryHints.resolveOrSuggest(DeviceCategories.PUMP, "Libre1", "血糖仪", null));
        assertEquals(DeviceCategories.CGM,
                DeviceCategoryHints.resolveOrSuggest(null, "Libre1", null, null));
    }
}
