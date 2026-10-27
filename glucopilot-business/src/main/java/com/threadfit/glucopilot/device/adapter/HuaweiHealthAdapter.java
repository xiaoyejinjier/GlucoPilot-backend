package com.threadfit.glucopilot.device.adapter;

import tools.jackson.databind.JsonNode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.adapter.support.MealSlotMapper;
import com.threadfit.glucopilot.device.adapter.support.UnitConversion;
import com.threadfit.glucopilot.device.model.UnifiedGlucoseSample;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 华为运动健康血糖：samplePoints 形态，或 data[].value.{beforeBreakfast:x} 键值形态。
 */
@Component
public class HuaweiHealthAdapter implements DevicePayloadAdapter {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public String provider() {
        return DeviceProviders.HUAWEI;
    }

    @Override
    public List<UnifiedGlucoseSample> adapt(JsonNode rawPayload) {
        if (rawPayload == null || rawPayload.isNull()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "HUAWEI payload 不能为空");
        }
        List<UnifiedGlucoseSample> samples = new ArrayList<>();
        if (rawPayload.has("samplePoints") && rawPayload.get("samplePoints").isArray()) {
            for (JsonNode point : rawPayload.get("samplePoints")) {
                samples.add(fromSamplePoint(point));
            }
        } else if (rawPayload.has("data") && rawPayload.get("data").isArray()) {
            for (JsonNode row : rawPayload.get("data")) {
                samples.addAll(fromKeyValueRow(row));
            }
        } else if (rawPayload.isArray()) {
            for (JsonNode row : rawPayload) {
                if (row.has("value") && row.get("value").isArray()) {
                    samples.add(fromSamplePoint(row));
                } else {
                    samples.addAll(fromKeyValueRow(row));
                }
            }
        } else {
            throw new BizException(ErrorCode.BAD_REQUEST, "无法识别的 HUAWEI payload");
        }
        if (samples.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "HUAWEI 无有效血糖样本");
        }
        return samples;
    }

    private static UnifiedGlucoseSample fromSamplePoint(JsonNode point) {
        BigDecimal level = null;
        Integer measureTime = null;
        JsonNode values = point.get("value");
        if (values != null && values.isArray()) {
            for (JsonNode field : values) {
                String name = text(field, "fieldName");
                if ("level".equals(name)) {
                    level = decimal(field, "floatValue", "integerValue");
                } else if ("measure_time".equals(name)) {
                    measureTime = field.has("integerValue") ? field.get("integerValue").asInt() : null;
                }
            }
        }
        if (level == null || measureTime == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "samplePoint 缺少 level/measure_time");
        }
        LocalDateTime at = parseTime(point, "startTime");
        String node = MealSlotMapper.fromHuaweiMeasureTime(measureTime);
        return new UnifiedGlucoseSample(
                at.toLocalDate(),
                node,
                UnitConversion.toMmolL(level, "MMOL_L"),
                at,
                textOrNull(point, "originalDataCollectorId"));
    }

    private static List<UnifiedGlucoseSample> fromKeyValueRow(JsonNode row) {
        JsonNode value = row.get("value");
        if (value == null || !value.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "HUAWEI data.value 无效");
        }
        LocalDateTime at = parseTime(row, "startTime");
        List<UnifiedGlucoseSample> list = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String node = MealSlotMapper.fromHuaweiValueKey(e.getKey());
            BigDecimal level = new BigDecimal(e.getValue().asText());
            list.add(new UnifiedGlucoseSample(
                    at.toLocalDate(),
                    node,
                    UnitConversion.toMmolL(level, "MMOL_L"),
                    at,
                    null));
        }
        return list;
    }

    private static LocalDateTime parseTime(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return LocalDateTime.now(ZONE);
        }
        long epoch = v.asLong();
        // 华为 REST 有时用纳秒
        if (epoch > 1_000_000_000_000_000L) {
            epoch = epoch / 1_000_000L;
        }
        if (epoch > 1_000_000_000_000L) {
            // 已是毫秒
        } else {
            epoch = epoch * 1000L;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZONE);
    }

    private static BigDecimal decimal(JsonNode field, String... names) {
        for (String name : names) {
            if (field.has(name) && !field.get(name).isNull()) {
                return new BigDecimal(field.get(name).asText());
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOrNull(JsonNode node, String field) {
        String t = text(node, field);
        return t == null || t.isBlank() ? null : t;
    }
}
