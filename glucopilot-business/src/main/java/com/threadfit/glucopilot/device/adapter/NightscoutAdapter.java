package com.threadfit.glucopilot.device.adapter;

import tools.jackson.databind.JsonNode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.adapter.support.TimeWindowNodeMapper;
import com.threadfit.glucopilot.device.adapter.support.UnitConversion;
import com.threadfit.glucopilot.device.model.UnifiedGlucoseSample;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nightscout / xDrip entries：sgv(mg/dL) + date/dateString；同日同节点取最后一条。
 */
@Component
public class NightscoutAdapter implements DevicePayloadAdapter {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public String provider() {
        return DeviceProviders.NIGHTSCOUT;
    }

    @Override
    public List<UnifiedGlucoseSample> adapt(JsonNode rawPayload) {
        JsonNode array = unwrapArray(rawPayload);
        Map<String, UnifiedGlucoseSample> lastByKey = new LinkedHashMap<>();
        for (JsonNode row : array) {
            String type = text(row, "type");
            if (StringUtils.hasText(type) && !"sgv".equalsIgnoreCase(type)) {
                continue;
            }
            if (!row.has("sgv") && !row.has("glucose")) {
                continue;
            }
            BigDecimal sgv = new BigDecimal(row.has("sgv") ? row.get("sgv").asText() : row.get("glucose").asText());
            BigDecimal mmol = UnitConversion.toMmolL(sgv, "MG_DL");
            LocalDateTime at = parseTime(row);
            String node = TimeWindowNodeMapper.resolve(at.toLocalTime());
            String externalId = text(row, "_id");
            if (externalId == null) {
                externalId = text(row, "dateString");
            }
            UnifiedGlucoseSample sample = new UnifiedGlucoseSample(
                    at.toLocalDate(), node, mmol, at, externalId);
            lastByKey.put(sample.recordDate() + "|" + sample.nodeCode(), sample);
        }
        if (lastByKey.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "NIGHTSCOUT 无有效 sgv 样本");
        }
        return new ArrayList<>(lastByKey.values());
    }

    private static JsonNode unwrapArray(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "NIGHTSCOUT payload 不能为空");
        }
        if (raw.isArray()) {
            return raw;
        }
        if (raw.has("entries") && raw.get("entries").isArray()) {
            return raw.get("entries");
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "NIGHTSCOUT payload 须为数组");
    }

    private static LocalDateTime parseTime(JsonNode row) {
        if (row.has("date") && !row.get("date").isNull()) {
            long epoch = row.get("date").asLong();
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZONE);
        }
        String dateString = text(row, "dateString");
        if (StringUtils.hasText(dateString)) {
            return OffsetDateTime.parse(dateString).atZoneSameInstant(ZONE).toLocalDateTime();
        }
        return LocalDateTime.now(ZONE);
    }

    private static String text(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull() || !StringUtils.hasText(v.asText())) {
            return null;
        }
        return v.asText().trim();
    }
}
