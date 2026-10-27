package com.threadfit.glucopilot.device.adapter;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.UnitConversion;
import com.threadfit.glucopilot.device.model.UnifiedGlucoseSample;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MockDeviceAdapterTest {

    private final MockDeviceAdapter adapter = new MockDeviceAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mmolPassthrough() {
        assertEquals(new BigDecimal("5.6"), UnitConversion.toMmolL(new BigDecimal("5.6"), "MMOL_L"));
        assertEquals(new BigDecimal("5.6"), UnitConversion.toMmolL(new BigDecimal("5.6"), null));
    }

    @Test
    void mgdlConverts() {
        assertEquals(new BigDecimal("5.6"), UnitConversion.toMmolL(new BigDecimal("100"), "MG_DL"));
    }

    @Test
    void unknownUnitFails() {
        assertThrows(BizException.class, () -> UnitConversion.toMmolL(new BigDecimal("5"), "PERCENT"));
    }

    @Test
    void adaptReadsPayload() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode row = root.putArray("readings").addObject();
        row.put("recordDate", "2026-10-14");
        row.put("nodeCode", "before_breakfast");
        row.put("value", 5.6);
        row.put("unit", "MMOL_L");

        List<UnifiedGlucoseSample> samples = adapter.adapt(root);
        assertEquals(1, samples.size());
        assertEquals("before_breakfast", samples.getFirst().nodeCode());
    }

    @Test
    void emptyPayloadFails() {
        BizException error = assertThrows(BizException.class, () -> adapter.adapt(mapper.createObjectNode()));
        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
    }
}
