package com.threadfit.glucopilot.device.adapter;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.port.GlucoseIngestPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class MockDeviceAdapterTest {

    private final MockDeviceAdapter adapter = new MockDeviceAdapter(mock(GlucoseIngestPort.class));

    @Test
    void mmolPassthrough() {
        assertEquals(new BigDecimal("5.6"), MockDeviceAdapter.toMmolL(new BigDecimal("5.6"), "MMOL_L"));
        assertEquals(new BigDecimal("5.6"), MockDeviceAdapter.toMmolL(new BigDecimal("5.6"), null));
    }

    @Test
    void mgdlConverts() {
        // 100 mg/dL * 0.0555 = 5.55 → 5.6
        assertEquals(new BigDecimal("5.6"), MockDeviceAdapter.toMmolL(new BigDecimal("100"), "MG_DL"));
    }

    @Test
    void unknownUnitFails() {
        assertThrows(BizException.class, () -> MockDeviceAdapter.toMmolL(new BigDecimal("5"), "PERCENT"));
    }
}
