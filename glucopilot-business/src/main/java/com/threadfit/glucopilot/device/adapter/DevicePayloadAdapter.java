package com.threadfit.glucopilot.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.threadfit.glucopilot.device.model.UnifiedGlucoseSample;

import java.util.List;

/**
 * 厂商原始 payload → 统一血糖样本。禁止写库。
 */
public interface DevicePayloadAdapter {

    String provider();

    List<UnifiedGlucoseSample> adapt(JsonNode rawPayload);
}
