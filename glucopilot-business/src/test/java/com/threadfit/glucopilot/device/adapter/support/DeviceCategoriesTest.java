package com.threadfit.glucopilot.device.adapter.support;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceCategoriesTest {

    @Test
    void blankBecomesNull() {
        assertNull(DeviceCategories.normalize(null));
        assertNull(DeviceCategories.normalize(""));
        assertNull(DeviceCategories.normalize("  "));
    }

    @Test
    void knownCategoriesNormalize() {
        assertEquals("CGM", DeviceCategories.normalize("cgm"));
        assertEquals("BGM", DeviceCategories.normalize("BGM"));
        assertEquals("BAND", DeviceCategories.normalize("band"));
        assertEquals("WATCH", DeviceCategories.normalize("Watch"));
        assertEquals("PUMP", DeviceCategories.normalize("pump"));
        assertEquals("PEN", DeviceCategories.normalize("PEN"));
        assertEquals("RING", DeviceCategories.normalize("ring"));
        assertEquals("HUB", DeviceCategories.normalize("Hub"));
        assertEquals("SCALE", DeviceCategories.normalize("scale"));
        assertEquals("BP", DeviceCategories.normalize("bp"));
        assertEquals("OXIMETER", DeviceCategories.normalize("oximeter"));
        assertEquals("OTHER", DeviceCategories.normalize("other"));
    }

    @Test
    void expandedSetIncludesWearables() {
        assertTrue(DeviceCategories.ALL.contains("BAND"));
        assertTrue(DeviceCategories.ALL.contains("WATCH"));
        assertTrue(DeviceCategories.ALL.contains("RING"));
    }

    @Test
    void unknownCategoryRejected() {
        BizException ex = assertThrows(BizException.class, () -> DeviceCategories.normalize("DRONE"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}
