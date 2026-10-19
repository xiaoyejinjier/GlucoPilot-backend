package com.threadfit.glucopilot.device.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

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
        private String userId;
        private String userMasked;
        private Integer deviceStatus;
        private LocalDateTime lastSyncAt;
        private LocalDateTime createdAt;
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
        private Long userId;
    }

    @Data
    public static class UpdateRequest {
        @NotBlank
        @Size(max = 64)
        private String deviceName;
        @Size(max = 32)
        private String provider;
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
