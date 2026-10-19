package com.threadfit.glucopilot.device.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.util.List;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    @Data
    public static class SyncRequest {
        /** MOCK / HUAWEI / HEALTH_CONNECT / APPLE_HEALTH / NIGHTSCOUT */
        @NotBlank
        private String provider;
        /** 可选：传入时校验归属当前用户且设备启用，成功后刷新 last_sync_at */
        private String deviceSn;
        /** 厂商原始报文；由对应适配器解析 */
        @NotNull
        private JsonNode payload;
    }

    @Data
    @Builder
    public static class SyncResultItem {
        private String nodeCode;
        private String recordId;
        private Boolean created;
    }

    @Data
    @Builder
    public static class SyncResponse {
        private String provider;
        private List<SyncResultItem> items;
    }
}
