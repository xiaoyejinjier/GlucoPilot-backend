package com.threadfit.glucopilot.device.adapter;

import tools.jackson.databind.JsonNode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.adapter.support.MealSlotMapper;
import com.threadfit.glucopilot.device.adapter.support.UnitConversion;
import com.threadfit.glucopilot.device.model.UnifiedGlucoseSample;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Apple HealthKit 导出风格：quantity + unit + startDate + meal metadata。
 */
@Component
public class AppleHealthAdapter implements DevicePayloadAdapter {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public String provider() {
        return DeviceProviders.APPLE_HEALTH;
    }

    @Override
    public List<UnifiedGlucoseSample> adapt(JsonNode rawPayload) {
        JsonNode array = unwrapArray(rawPayload);
        List<UnifiedGlucoseSample> samples = new ArrayList<>();
        for (JsonNode row : array) {
            if (!row.has("quantity") && !row.has("value")) {
                throw new BizException(ErrorCode.BAD_REQUEST, "APPLE_HEALTH 样本缺少 quantity");
            }
            BigDecimal quantity = new BigDecimal(
                    row.has("quantity") ? row.get("quantity").asText() : row.get("value").asText());
            String unit = text(row, "unit");
            BigDecimal mmol = UnitConversion.toMmolL(quantity, unit);
            LocalDateTime at = parseTime(row);
            String mealHint = mealHint(row);
            String node = MealSlotMapper.fromMealRelation(mealHint, at.toLocalTime());
            String externalId = text(row, "uuid");
            if (externalId == null && row.has("metadata")) {
                JsonNode meta = row.get("metadata");
                if (meta.has("HKExternalUUID")) {
                    externalId = meta.get("HKExternalUUID").asText();
                }
            }
            samples.add(new UnifiedGlucoseSample(at.toLocalDate(), node, mmol, at, externalId));
        }
        if (samples.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "APPLE_HEALTH 无有效血糖样本");
        }
        return samples;
    }

    private static JsonNode unwrapArray(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "APPLE_HEALTH payload 不能为空");
        }
        if (raw.isArray()) {
            return raw;
        }
        if (raw.has("samples") && raw.get("samples").isArray()) {
            return raw.get("samples");
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "APPLE_HEALTH payload 须为数组");
    }

    private static String mealHint(JsonNode row) {
        if (row.has("metadata") && row.get("metadata").has("HKMetadataKeyBloodGlucoseMealTime")) {
            return row.get("metadata").get("HKMetadataKeyBloodGlucoseMealTime").asText();
        }
        return text(row, "mealTime");
    }

    private static LocalDateTime parseTime(JsonNode row) {
        String text = text(row, "startDate");
        if (text == null) {
            text = text(row, "endDate");
        }
        if (!StringUtils.hasText(text)) {
            return LocalDateTime.now(ZONE);
        }
        if (text.endsWith("Z") || text.contains("+") || text.matches(".*-\\d{2}:\\d{2}$")) {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZONE).toLocalDateTime();
        }
        return LocalDateTime.parse(text);
    }

    private static String text(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull() || !StringUtils.hasText(v.asText())) {
            return null;
        }
        return v.asText().trim();
    }
}
