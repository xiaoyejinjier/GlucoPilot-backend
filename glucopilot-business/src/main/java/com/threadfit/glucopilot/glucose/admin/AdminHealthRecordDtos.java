package com.threadfit.glucopilot.glucose.admin;

import com.threadfit.glucopilot.common.api.PageResult;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 管理端九时段记录可视化 DTO。 */
public final class AdminHealthRecordDtos {

    private AdminHealthRecordDtos() {
    }

    @Data
    @Builder
    public static class Result {
        private LocalDate startDate;
        private LocalDate endDate;
        private Overview overview;
        private PageResult<DayRow> page;
    }

    @Data
    @Builder
    public static class Overview {
        private Long activeUsers;
        /** 有任一节点记录的“用户-日期”数量。 */
        private Long recordDays;
        private Long glucoseRecords;
        private Long mealRecords;
        private Long totalRecords;
        private BigDecimal avgGlucose;
        private Integer complianceRate;
        private Long abnormalRecords;
        private List<TrendPoint> trend;
        private List<NodeStat> nodeStats;
    }

    @Data
    @Builder
    public static class TrendPoint {
        private LocalDate date;
        private Long glucoseCount;
        private Long mealCount;
        private BigDecimal avgGlucose;
    }

    @Data
    @Builder
    public static class NodeStat {
        private String nodeCode;
        private String nodeName;
        private Boolean meal;
        private Long recordCount;
        /** 餐点节点固定为空，禁止把饮食预测值当成实测血糖。 */
        private BigDecimal avgGlucose;
    }

    @Data
    @Builder
    public static class DayRow {
        private String userId;
        private String username;
        private String nickname;
        private LocalDate recordDate;
        private Integer completedCount;
        private Integer glucoseCount;
        private Integer mealCount;
        private BigDecimal avgGlucose;
        /** LinkedHashMap，顺序与九时段字典 sort_no 一致。 */
        private Map<String, Slot> slots;
    }

    @Data
    @Builder
    public static class Slot {
        private String nodeCode;
        private String nodeName;
        private Boolean meal;
        private Boolean recorded;
        private BigDecimal glucoseValue;
        private Integer sourceType;
        private String remark;
        private String foodsText;
        private BigDecimal carbsG;
        private BigDecimal predictNow;
        private BigDecimal predictSuggest;
        private LocalDateTime recordedAt;
    }
}
