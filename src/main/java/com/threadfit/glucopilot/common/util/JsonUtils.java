package com.threadfit.glucopilot.common.util;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON 工具：复用 Spring 注入的 ObjectMapper（Boot 4 / Jackson 3）。
 */
@Component
public class JsonUtils {

    private final ObjectMapper objectMapper;

    public JsonUtils(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "JSON 序列化失败");
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "JSON 解析失败");
        }
    }
}
