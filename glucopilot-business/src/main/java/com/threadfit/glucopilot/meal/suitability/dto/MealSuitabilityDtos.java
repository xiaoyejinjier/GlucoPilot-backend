package com.threadfit.glucopilot.meal.suitability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 适食分析 DTO（阶段 1a）。
 * <p>{@code ItemInput} 按阶段 2 {@code MealNutritionResolver} 的最终形状预留字段：
 * 1a 由请求入参直接构造，1b 只替换数据来源，不改引擎签名。
 */
public final class MealSuitabilityDtos {

    private MealSuitabilityDtos() {
    }

    /** 引擎输入：单个食物项（canonical 后的营养值）。 */
    @Data
    public static class ItemInput {
        /** 营养库食物 ID，可空（自定义/手输时为空） */
        private Long foodId;
        /** 同 foodId，随阶段 2 resolver 保留，用于区分识别来源 */
        private Long libraryFoodId;
        /** 食物名称，用于回显与文案拼接 */
        private String foodName;
        /** 食物大类 1-9，可空；为空时分类规则不参与匹配 */
        private Integer categoryCode;
        /** 份量克数 g，可空 */
        private BigDecimal weightG;
        /** 该份量下的碳水 g，可空 */
        private BigDecimal carbsG;
        /** 该份量下的热量 kcal，可空 */
        private BigDecimal caloriesKcal;
        /** GI 值，可空；为空时 GI 规则不参与匹配 */
        private BigDecimal giValue;
        /** 营养来源：LIBRARY|AI_ESTIMATE|MANUAL，可空 */
        private String nutritionSource;
        /** 识别置信度 0-100，可空 */
        private Integer confidence;
        /** 用户是否手动修正过，可空 */
        private Boolean userCorrected;
        /**
         * 营养库匹配状态：AVAILABLE|PENDING_REVIEW|UNRESOLVED，可空。
         * <p>引擎不消费该字段；仅供服务端判定 needsConfirmation 与前端标黄，由 resolver 填充。
         */
        private String libraryStatus;
    }

    /** 引擎输出：单个食物项的判定结果。 */
    @Data
    public static class ItemResult {
        private Long foodId;
        private String foodName;
        /** LOW|MODERATE|HIGH|UNKNOWN */
        private String tier;
        /** 命中规则编码；UNKNOWN 时为 null */
        private String ruleCode;
        /** 归一化后的食物大类（可能由库补全），由响应组装层回填，引擎不消费 */
        private Integer categoryCode;
        /** 归一化后的 GI（可能由库补全），由响应组装层回填，引擎不消费 */
        private BigDecimal giValue;
        /** 营养库匹配状态 AVAILABLE|PENDING_REVIEW|UNRESOLVED，由响应组装层回填 */
        private String libraryStatus;
        /** 归一化后的营养来源 LIBRARY|AI_ESTIMATE|MANUAL|UNKNOWN，由响应组装层回填 */
        private String nutritionSource;
    }

    /** 引擎输出：整餐判定结果。 */
    @Data
    public static class Evaluation {
        private List<ItemResult> items;
        /** 整餐档位：取最差项；全部信息不足为 UNKNOWN */
        private String overallTier;
        /** 驱动整餐结论的规则编码；整餐 UNKNOWN 时为 null */
        private String worstRuleCode;
    }

    /** 预览请求（POST /api/v1/meal-suitability/preview）。 */
    @Data
    public static class PreviewRequest {
        /** 餐点节点编码；1a 仅透传，不参与判定 */
        private String nodeCode;
        /** 规则版本；为空时取表内最新启用版本 */
        private String ruleVersion;
        /** 食物项，至少 1 条 */
        @NotEmpty
        private List<@Valid ItemInput> items;
    }

    /** 预览响应：单个食物项。 */
    @Data
    public static class PreviewItem {
        private Long foodId;
        private String foodName;
        /** LOW|MODERATE|HIGH|UNKNOWN */
        private String tier;
        /** 升糖负担较低/中等/较高/信息不足 */
        private String tierLabel;
        /** 规则级单项建议（不含食物名，可空）；为空时服务端按档位兜底 */
        private String adviceText;
        /** 命中规则编码；UNKNOWN 时为 null */
        private String ruleCode;
        /** 归一化后的食物大类（展示值与判定依据保持一致） */
        private Integer categoryCode;
        /** 归一化后的 GI（展示值与判定依据保持一致） */
        private BigDecimal giValue;
        /** 营养库匹配状态 AVAILABLE|PENDING_REVIEW|UNRESOLVED */
        private String libraryStatus;
        /** 归一化后的营养来源 LIBRARY|AI_ESTIMATE|MANUAL|UNKNOWN */
        private String nutritionSource;
    }

    /** 预览响应：整餐。 */
    @Data
    public static class PreviewResponse {
        /** 整餐档位：LOW|MODERATE|HIGH|UNKNOWN */
        private String tier;
        private String tierLabel;
        /** 本次判定实际使用的规则版本 */
        private String ruleVersion;
        /** 驱动整餐结论的规则编码；整餐 UNKNOWN 时为 null */
        private String worstRuleCode;
        private List<PreviewItem> items;
        /** 免责声明（定稿待法务/营养师双签） */
        private String disclaimer;
    }
}
