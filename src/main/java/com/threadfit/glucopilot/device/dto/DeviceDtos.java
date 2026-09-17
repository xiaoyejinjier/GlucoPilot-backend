package com.threadfit.glucopilot.device.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    @Data
    public static class SyncRequest {
        /** MOCK / 未来厂商编码 */
        @NotBlank
        private String provider;
        @NotEmpty
        @Valid
        private List<Reading> readings;
    }

    @Data
    public static class Reading {
        @NotNull
        private LocalDate recordDate;
        @NotBlank
        private String nodeCode;
        @NotNull
        private BigDecimal value;
        /** MMOL_L（默认）或 MG_DL，换算仅在 Adapter 内完成 */
        private String unit;
        /** 厂商侧记录 ID，用于幂等备注 */
        private String externalRecordId;
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
