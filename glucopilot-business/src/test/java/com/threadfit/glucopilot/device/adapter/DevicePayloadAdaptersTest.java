package com.threadfit.glucopilot.device.adapter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.device.adapter.support.UnitConversion;
import com.threadfit.glucopilot.device.model.UnifiedGlucoseSample;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevicePayloadAdaptersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mockFixture() throws Exception {
        List<UnifiedGlucoseSample> samples = new MockDeviceAdapter().adapt(load("mock.json"));
        assertEquals(1, samples.size());
        assertEquals(GlucoseNodes.BEFORE_BREAKFAST, samples.getFirst().nodeCode());
        assertEquals(new BigDecimal("5.6"), samples.getFirst().glucoseMmolL());
    }

    @Test
    void huaweiFixture() throws Exception {
        List<UnifiedGlucoseSample> samples = new HuaweiHealthAdapter().adapt(load("huawei.json"));
        assertEquals(1, samples.size());
        assertEquals(GlucoseNodes.BEFORE_BREAKFAST, samples.getFirst().nodeCode());
        assertEquals(new BigDecimal("6.7"), samples.getFirst().glucoseMmolL());
    }

    @Test
    void healthConnectFixture() throws Exception {
        List<UnifiedGlucoseSample> samples = new HealthConnectAdapter().adapt(load("health_connect.json"));
        assertEquals(1, samples.size());
        assertEquals(GlucoseNodes.BEFORE_BREAKFAST, samples.getFirst().nodeCode());
        assertEquals(new BigDecimal("5.8"), samples.getFirst().glucoseMmolL());
    }

    @Test
    void appleHealthFixture() throws Exception {
        List<UnifiedGlucoseSample> samples = new AppleHealthAdapter().adapt(load("apple_health.json"));
        assertEquals(1, samples.size());
        assertEquals(GlucoseNodes.BEFORE_BREAKFAST, samples.getFirst().nodeCode());
        assertEquals(new BigDecimal("5.6"), samples.getFirst().glucoseMmolL());
    }

    @Test
    void nightscoutFixtureKeepsLastPerNode() throws Exception {
        List<UnifiedGlucoseSample> samples = new NightscoutAdapter().adapt(load("nightscout.json"));
        assertFalse(samples.isEmpty());
        assertEquals(1, samples.size());
        assertEquals(new BigDecimal("6.7"), samples.getFirst().glucoseMmolL());
    }

    @Test
    void unitConversion() {
        assertEquals(new BigDecimal("5.6"), UnitConversion.toMmolL(new BigDecimal("5.6"), "MMOL_L"));
        assertEquals(new BigDecimal("5.6"), UnitConversion.toMmolL(new BigDecimal("100"), "MG_DL"));
        assertThrows(RuntimeException.class, () -> UnitConversion.toMmolL(new BigDecimal("5"), "PERCENT"));
    }

    private JsonNode load(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/device/payloads/" + name)) {
            return mapper.readTree(in);
        }
    }
}
