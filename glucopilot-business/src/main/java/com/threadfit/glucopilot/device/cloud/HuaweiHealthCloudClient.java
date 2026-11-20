package com.threadfit.glucopilot.device.cloud;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 华为 Health Kit 云端血糖拉取，输出与 {@code HuaweiHealthAdapter} 兼容的 samplePoints JSON。
 */
@Component
public class HuaweiHealthCloudClient {

    private final HuaweiCloudProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HuaweiHealthCloudClient(
            DeviceCloudProperties cloudProperties,
            @Qualifier("deviceCloudRestClient") RestClient restClient,
            ObjectMapper objectMapper) {
        this.props = cloudProperties.getHuawei() != null ? cloudProperties.getHuawei() : new HuaweiCloudProperties();
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 拉取近 {@code days} 天血糖样本，归一为 {@code { "samplePoints": [...] }}。
     */
    public JsonNode fetchGlucoseSamplePoints(String accessToken, int days) {
        if (!StringUtils.hasText(accessToken)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "缺少华为访问令牌");
        }
        Instant end = Instant.now();
        Instant start = end.minus(Math.max(1, days), ChronoUnit.DAYS);
        URI uri = UriComponentsBuilder.fromUriString(trimSlash(props.getApiBase()) + "/sampleSet:polymerize")
                .queryParam("startTime", start.toEpochMilli())
                .queryParam("endTime", end.toEpochMilli())
                .build(true)
                .toUri();

        ObjectNode requestBody = objectMapper.createObjectNode();
        ArrayNode dataTypes = requestBody.putArray("polymerizeWith");
        ObjectNode bloodGlucose = dataTypes.addObject();
        bloodGlucose.put("dataTypeName", "com.huawei.instantaneous.blood_glucose");

        try {
            String raw = restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(requestBody))
                    .retrieve()
                    .body(String.class);
            return normalize(objectMapper.readTree(Objects.requireNonNullElse(raw, "{}")));
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "华为健康云拉取失败");
        }
    }

    /**
     * 将华为原始响应尽量规整为适配器可解析的 samplePoints；若已是该形态则原样包装。
     */
    JsonNode normalize(JsonNode root) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode points = out.putArray("samplePoints");
        if (root == null || root.isNull()) {
            return out;
        }
        if (root.has("samplePoints") && root.get("samplePoints").isArray()) {
            root.get("samplePoints").forEach(points::add);
            return out;
        }
        JsonNode group = root.get("group");
        if (group != null && group.isArray()) {
            for (JsonNode g : group) {
                JsonNode sampleSet = g.get("sampleSet");
                if (sampleSet == null || !sampleSet.isArray()) {
                    continue;
                }
                for (JsonNode set : sampleSet) {
                    JsonNode samplePoints = set.get("samplePoints");
                    if (samplePoints != null && samplePoints.isArray()) {
                        samplePoints.forEach(points::add);
                    }
                }
            }
        }
        return out;
    }

    private static String trimSlash(String base) {
        if (!StringUtils.hasText(base)) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
