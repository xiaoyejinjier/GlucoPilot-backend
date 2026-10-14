package com.threadfit.glucopilot.ai.business.ocr.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class OcrDtos {

    private OcrDtos() {
    }

    @Data
    public static class CreateRequest {
        @NotNull
        private Long mediaId;

        /** 1=血糖；2=餐食（本期未开放）。 */
        @NotNull
        private Integer bizType;
    }

    /** 视觉模型的稳定结构化输出，同时也是 ocr_result_json 的 schemaVersion=1 契约。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GlucoseResult {
        private Integer schemaVersion;
        private Integer bizType;
        private BigDecimal suggestedValue;
        private Integer confidence;
        private String rawText;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskView {
        /** 字符串返回，避免小程序 JavaScript 丢失 BIGINT 精度。 */
        private String id;
        private String mediaId;
        private Integer bizType;
        private Integer taskStatus;
        private GlucoseResult ocrResult;
        private String errorMsg;
        private LocalDateTime finishedAt;
    }
}
