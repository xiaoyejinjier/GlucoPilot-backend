package com.threadfit.glucopilot.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.adapter.support.UnitConversion;
import com.threadfit.glucopilot.device.model.UnifiedGlucoseSample;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MOCK：payload = { "readings": [ { recordDate, nodeCode, value, unit, externalRecordId } ] }
 */
@Component
public class MockDeviceAdapter implements DevicePayloadAdapter {

    @Override
    public String provider() {
        return DeviceProviders.MOCK;
    }

    @Override
    public List<UnifiedGlucoseSample> adapt(JsonNode rawPayload) {
        if (rawPayload == null || rawPayload.isNull()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MOCK payload 不能为空");
        }
        JsonNode readings = rawPayload.has("readings") ? rawPayload.get("readings") : rawPayload;
        if (readings == null || !readings.isArray() || readings.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MOCK readings 不能为空");
        }
        List<UnifiedGlucoseSample> samples = new ArrayList<>();
        for (JsonNode row : readings) {
            if (row == null || row.isNull()) {
                continue;
            }
            LocalDate recordDate = LocalDate.parse(requireText(row, "recordDate"));
            String nodeCode = requireText(row, "nodeCode");
            if (!GlucoseNodes.isGlucoseNode(nodeCode)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "非法血糖节点: " + nodeCode);
            }
            BigDecimal value = new BigDecimal(requireText(row, "value"));
            String unit = textOrNull(row, "unit");
            BigDecimal mmol = UnitConversion.toMmolL(value, unit);
            String externalId = textOrNull(row, "externalRecordId");
            samples.add(new UnifiedGlucoseSample(recordDate, nodeCode, mmol, LocalDateTime.now(), externalId));
        }
        if (samples.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MOCK readings 不能为空");
        }
        return samples;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !StringUtils.hasText(v.asText())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少字段: " + field);
        }
        return v.asText().trim();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !StringUtils.hasText(v.asText())) {
            return null;
        }
        return v.asText().trim();
    }
}
