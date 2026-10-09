package com.threadfit.glucopilot.glucose.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** C 端血糖记录 / 日看板 / 时段字典 DTO。 */
public final class GlucoseDtos {

    private GlucoseDtos() {
    }

    /**
     * 按 (recordDate, nodeCode) 覆盖写入。DTO 层只挡住明显非法值，
     * 「必须是非餐点节点」等业务规则在 Service 校验。
     */
    @Data
    public static class UpsertRequest {
        @NotNull
        private LocalDate recordDate;
        @NotBlank
        private String nodeCode;
        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal glucoseValue;
        /** 1手动2OCR3设备；缺省按手动处理 */
        private Integer sourceType;
        private Long mediaId;
        private String remark;
    }

    @Data
    @Builder
    public static class UpsertResult {
        private String id;
        private Boolean created;
    }

    /** 部分修改：remark 传空串表示清空，传 null（或省略）表示不改。 */
    @Data
    public static class PatchRequest {
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal glucoseValue;
        private String remark;
        private LocalDateTime recordedAt;
    }

    @Data
    @Builder
    public static class RecordItem {
        private String id;
        private LocalDate recordDate;
        private String nodeCode;
        private String nodeName;
        private BigDecimal glucoseValue;
        private Integer sourceType;
        private String mediaId;
        private String remark;
        private LocalDateTime recordedAt;
    }

    @Data
    @Builder
    public static class GlucoseBrief {
        private BigDecimal glucoseValue;
        private Integer sourceType;
        private String remark;
    }

    /** 日看板单个节点：血糖节点带 glucose，餐点节点本期固定 hasRecord=false。 */
    @Data
    @Builder
    public static class NodeSlot {
        private String nodeCode;
        private String nodeName;
        private Boolean isMeal;
        private Boolean hasRecord;
        /** glucose / meal；无记录为 null */
        private String recordKind;
        private GlucoseBrief glucose;
    }

    @Data
    @Builder
    public static class ChartPoint {
        private String nodeCode;
        private BigDecimal value;
    }

    @Data
    @Builder
    public static class DayBoard {
        private LocalDate recordDate;
        private Integer recordedCount;
        private Integer totalNodes;
        private List<NodeSlot> nodes;
        private List<ChartPoint> chartPoints;
    }

    @Data
    @Builder
    public static class NodeItem {
        private String nodeCode;
        private String nodeName;
        private String shortName;
        private Boolean isMeal;
        private Integer sortNo;
    }

    /** 分时段统计：早餐段 / 午餐段 / 晚餐段 / 其他。 */
    @Data
    @Builder
    public static class SlotStat {
        private String nodeCode;
        private String name;
        private Integer count;
        private BigDecimal avg;
        /** 相对记录最多时段的百分比，用于端上进度条 */
        private Integer pct;
    }

    /** 按天聚合的趋势点；无记录的天 count=0、avg=null。 */
    @Data
    @Builder
    public static class TrendPoint {
        private LocalDate date;
        private BigDecimal avg;
        private Integer count;
    }
}
