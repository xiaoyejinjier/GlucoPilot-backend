package com.threadfit.glucopilot.meal.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** C 端饮食记录 VO 集合。 */
public final class MealVos {

    private MealVos() {
    }

    /** 保存结果：返回主记录 id 与本次是否新建（created=false 表示覆盖了既有记录）。 */
    @Data
    @Builder
    public static class SaveResult {
        private String id;
        private Boolean created;
        /** 分析状态：PENDING|READY|FAILED（对应 analysis_status 0/1/2） */
        private String analysisStatus;
        /** 保存前后使用的规则版本是否变化（用于提示前端刷新展示） */
        private Boolean ruleVersionChanged;
        /** 分析快照；分析失败或未生成时为 null */
        private Analysis analysis;
    }

    /** 按日查询结果：一个日期 + 该日全部餐点详情（按餐次排序）。 */
    @Data
    @Builder
    public static class DayMeals {
        private LocalDate recordDate;
        private List<MealDetail> meals;
    }

    /** 单餐点详情：主记录字段 + 明细列表 + 照片可访问 URL。 */
    @Data
    @Builder
    public static class MealDetail {
        private String id;
        /** 餐点节点编码（如早餐/午餐/晚餐） */
        private String nodeCode;
        /** 照片 ID（字符串化），无则 null */
        private String mediaId;
        /** 稳定照片相对路径；下载时须携带当前用户 Bearer，无则 null。 */
        private String mediaUrl;
        private String foodsText;
        private BigDecimal carbsG;
        /** 饥饿感：1饱腹 2一般 3微饿 */
        private Integer hungerLevel;
        private String supplementText;
        /** 记录保存时刻（服务端写入） */
        private LocalDateTime recordedAt;
        /** 分析状态：PENDING|READY|FAILED；历史记录为 null */
        private String analysisStatus;
        /** 分析快照；未分析/失败时为 null */
        private Analysis analysis;
        /** 只读血糖上下文；对应时段无血糖记录时为 null */
        private GlucoseContext glucoseContext;
        private List<MealItemDetail> items;
    }

    /** 管理端健康档案：近期饮食记录汇总（窗口内活跃记录）。 */
    @Data
    @Builder
    public static class ArchiveSummary {
        /** 窗口内有饮食记录的天数 */
        private Integer recordDays;
        /** 平均每餐碳水（g，按主记录 carbsG 均值，无数据为 null） */
        private BigDecimal avgCarbsG;
        /** 平均每餐热量（kcal，按明细热量求和后取均餐值，无数据为 null） */
        private BigDecimal avgCal;
        /** 高频食物名（按明细出现次数降序，最多 4 个） */
        private List<String> topFoods;
    }

    /** 单行食物明细 VO。 */
    @Data
    @Builder
    public static class MealItemDetail {
        /** 营养库食物 ID（手输时为 null），字符串化 */
        private String foodId;
        /** 营养库待审行 ID，字符串化；无则 null */
        private String libraryFoodId;
        private String foodName;
        /** 所属菜品名（食材级拆分后的分组名），可空 */
        private String dishName;
        /** MAIN|SEASONING，可空 */
        private String role;
        /** 烹饪方式，可空 */
        private String cookMethod;
        private BigDecimal weightG;
        private BigDecimal carbsG;
        private BigDecimal caloriesKcal;
        /** 食物大类 1-9 */
        private Integer categoryCode;
        /** GI 快照 */
        private BigDecimal giValue;
        /** GI 来源：LIBRARY|CATEGORY_FALLBACK|NONE */
        private String giSource;
        /** 营养来源：LIBRARY|AI_ESTIMATE|MANUAL|UNKNOWN */
        private String nutritionSource;
        /** 识别置信度 0-100 */
        private Integer confidence;
        /** 是否人工修正 */
        private Boolean userCorrected;
        /** 份量倍数 */
        private BigDecimal portionRatio;
        /** 单项结论档位 */
        private String adviceTier;
        /** 单项规则原因码 */
        private String adviceReasonCode;
        /** 单项通用建议文案 */
        private String adviceText;
    }

    /** 分析快照（整餐 + 单项）。 */
    @Data
    @Builder
    public static class Analysis {
        /** LOW|MODERATE|HIGH|UNKNOWN */
        private String tier;
        /** 升糖负担较低/中等/较高/信息不足 */
        private String tierLabel;
        /** 整餐摘要（通用文案） */
        private String summary;
        /** 判定所用规则版本 */
        private String ruleVersion;
        /** 重点关注项列表（单项 HIGH 的标记，只标注元凶不改整餐档位） */
        private List<AnalysisFocusItem> focusItems;
        private List<AnalysisItem> items;
    }

    /** 分析快照中的重点关注项。 */
    @Data
    @Builder
    public static class AnalysisFocusItem {
        private String foodName;
        /** INTRINSIC=本身偏高|PORTION=份量偏大 */
        private String reason;
        /** 展示文案（{foodName} 已替换） */
        private String text;
    }

    /** 分析快照中的单项结论。 */
    @Data
    @Builder
    public static class AnalysisItem {
        private String foodName;
        /** LOW|MODERATE|HIGH|UNKNOWN */
        private String tier;
        /** 命中的规则编码；UNKNOWN 时为 null */
        private String ruleCode;
        /** 单项通用建议文案 */
        private String advice;
    }

    /** 只读血糖上下文：对应餐点前后两条血糖记录，缺失字段为 null。 */
    @Data
    @Builder
    public static class GlucoseContext {
        private BigDecimal before;
        private BigDecimal after;
        private BigDecimal delta;
    }
}
