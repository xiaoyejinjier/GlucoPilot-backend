package com.threadfit.glucopilot.device.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class AdminDeviceDtos {

    private AdminDeviceDtos() {
    }

    @Data
    @Builder
    public static class DeviceItem {
        private String id;
        private String deviceSn;
        private String deviceName;
        private String provider;
        private String deviceCategory;
        private String deviceModel;
        private String userId;
        private String userMasked;
        private Integer deviceStatus;
        private LocalDateTime lastSyncAt;
        private LocalDateTime createdAt;
        /** 是否关联有效厂商云授权 */
        private Boolean cloudLinked;
        /** OK / NEVER / STALE（超过 48h 或未同步中 STALE 与 NEVER 区分） */
        private String syncHealth;
    }

    @Data
    @Builder
    public static class SyncLogItem {
        private String id;
        private String provider;
        private Integer sampleCount;
        private Integer createdCount;
        private String remark;
        private LocalDateTime syncedAt;
    }

    @Data
    @Builder
    public static class SyncSamplePoint {
        private LocalDateTime recordedAt;
        private String nodeCode;
        private BigDecimal glucoseValue;
    }

    @Data
    @Builder
    public static class SyncSamplesResponse {
        private LocalDateTime syncedAt;
        private Integer sampleCount;
        private List<SyncSamplePoint> points;
    }

    @Data
    @Builder
    public static class CloudAuthStatus {
        private Boolean linked;
        private LocalDateTime expiresAt;
        private String scopes;
        /** 脱敏后的厂商侧主体，永不回传 token */
        private String vendorSubjectMasked;
    }

    @Data
    public static class CreateRequest {
        @NotBlank
        @Size(max = 64)
        private String deviceSn;
        @NotBlank
        @Size(max = 64)
        private String deviceName;
        @Size(max = 32)
        private String provider;
        @Size(max = 32)
        private String deviceCategory;
        @Size(max = 64)
        private String deviceModel;
        private Long userId;
    }

    @Data
    public static class UpdateRequest {
        @NotBlank
        @Size(max = 64)
        private String deviceName;
        @Size(max = 32)
        private String provider;
        @Size(max = 32)
        private String deviceCategory;
        @Size(max = 64)
        private String deviceModel;
        private Long userId;
    }

    @Data
    public static class StatusRequest {
        @NotNull
        @Min(0)
        @Max(1)
        private Integer deviceStatus;
    }

    @Data
    @Builder
    public static class UpdatedResult {
        private Boolean updated;
    }
}
