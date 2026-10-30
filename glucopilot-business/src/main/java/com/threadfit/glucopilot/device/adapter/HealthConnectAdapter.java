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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Android Health Connect / 三星 Health 风格瞬时血糖样本数组。
 */
@Component
public class HealthConnectAdapter implements DevicePayloadAdapter {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public String provider() {
        return DeviceProviders.HEALTH_CONNECT;
    }

    @Override
    public List<UnifiedGlucoseSample> adapt(JsonNode rawPayload) {
        JsonNode array = unwrapArray(rawPayload);
        List<UnifiedGlucoseSample> samples = new ArrayList<>();
        for (JsonNode row : array) {
            LocalDateTime at = parseTime(row);
            BigDecimal mmol = readLevel(row);
            String mealHint = firstText(row, "mealType", "relationToMeal", "mealStatus");
            String node = MealSlotMapper.fromMealRelation(mealHint, at.toLocalTime());
            String externalId = firstText(row, "id", "clientDataId", "metadata.id");
            samples.add(new UnifiedGlucoseSample(at.toLocalDate(), node, mmol, at, externalId));
        }
        if (samples.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "HEALTH_CONNECT 无有效血糖样本");
        }
        return samples;
    }

    private static JsonNode unwrapArray(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "HEALTH_CONNECT payload 不能为空");
        }
        if (raw.isArray()) {
            return raw;
        }
        if (raw.has("records") && raw.get("records").isArray()) {
            return raw.get("records");
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "HEALTH_CONNECT payload 须为数组");
    }

    private static BigDecimal readLevel(JsonNode row) {
        JsonNode level = row.get("level");
        if (level != null && level.isObject()) {
            if (level.has("millimolesPerLiter")) {
                return UnitConversion.toMmolL(new BigDecimal(level.get("millimolesPerLiter").asText()), "MMOL_L");
            }
            if (level.has("milligramsPerDeciliter")) {
                return UnitConversion.toMmolL(new BigDecimal(level.get("milligramsPerDeciliter").asText()), "MG_DL");
            }
        }
        if (row.has("value")) {
            String unit = firstText(row, "unit");
            return UnitConversion.toMmolL(new BigDecimal(row.get("value").asText()), unit);
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "缺少血糖 level/value");
    }

    private static LocalDateTime parseTime(JsonNode row) {
        String text = firstText(row, "time", "startTime", "endTime");
        if (!StringUtils.hasText(text)) {
            return LocalDateTime.now(ZONE);
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (DateTimeParseException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法日期: " + text);
        }
    }

    private static String firstText(JsonNode row, String... fields) {
        for (String field : fields) {
            if (field.contains(".")) {
                String[] parts = field.split("\\.");
                JsonNode cur = row;
                for (String p : parts) {
                    if (cur == null) {
                        break;
                    }
                    cur = cur.get(p);
                }
                if (cur != null && !cur.isNull() && StringUtils.hasText(cur.asText())) {
                    return cur.asText().trim();
                }
                continue;
            }
            JsonNode v = row.get(field);
            if (v != null && !v.isNull() && StringUtils.hasText(v.asText())) {
                return v.asText().trim();
            }
        }
        return null;
    }
}
