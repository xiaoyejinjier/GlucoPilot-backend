package com.threadfit.glucopilot.device.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
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

    /** C 端设备列表项（已归属当前用户）。 */
    @Data
    @Builder
    public static class DeviceItem {
        private String id;
        private String deviceSn;
        private String deviceName;
        private String provider;
        private Integer deviceStatus;
        private LocalDateTime lastSyncAt;
        /** 恒为 true：表示归属当前用户 */
        private Boolean bound;
    }

    /** 自助绑定：SN + provider，名称可选。 */
    @Data
    public static class BindRequest {
        @NotBlank
        @Size(max = 64)
        private String deviceSn;
        @NotBlank
        @Size(max = 32)
        private String provider;
        @Size(max = 64)
        private String deviceName;
    }

    /** 设备同步流水（近 N 次）。 */
    @Data
    @Builder
    public static class SyncLogItem {
        private String id;
        private String provider;
        private Integer sampleCount;
        private Integer createdCount;
        private String remark;
        /** 同步时间，对应 gmt_create */
        private LocalDateTime syncedAt;
    }
}
