package com.threadfit.glucopilot.meal.suitability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 适食分析 DTO。
 * <p>判定主指标为 per100gGL（每 100g 升糖负荷 = GI × 每100g碳水 / 100，食物固有属性）；
 * 份量只做离散量级修正，整餐按碳水占比最大项定档，不再 worst-of。
 */
public final class MealSuitabilityDtos {

    /** GI 来源：客户端输入 */
    public static final String GI_SOURCE_CLIENT = "CLIENT";
    /** GI 来源：营养库 */
    public static final String GI_SOURCE_LIBRARY = "LIBRARY";
    /** GI 可信度：客户端值与库值差异过大时置 LOW，提示“结果仅供参考” */
    public static final String GI_RELIABILITY_LOW = "LOW";

    /** 重点关注原因：单项本身 per100gGL 偏高 */
    public static final String FOCUS_INTRINSIC = "INTRINSIC";
    /** 重点关注原因：单项因份量修正升为 HIGH */
    public static final String FOCUS_PORTION = "PORTION";

    private MealSuitabilityDtos() {
    }

    /** 引擎输入：单个食物项（归一化后的营养值）。 */
    @Data
    public static class ItemInput {
        /** 营养库食物 ID，可空（自定义/手输时为空） */
        private Long foodId;
        /** 营养库行 ID，随 resolver 保留，用于区分识别来源 */
        private Long libraryFoodId;
        /** 食物名称，用于回显与文案拼接 */
        private String foodName;
        /** 食物大类 1-9，可空；仅作展示与 CARB/GI 规则匹配，不参与 GL 判定 */
        private Integer categoryCode;
        /** 份量克数 g，可空 */
        private BigDecimal weightG;
        /** 该份量下的碳水 g，可空；份量量级修正与整餐占比聚合使用 */
        private BigDecimal carbsG;
        /** 该份量下的热量 kcal，可空 */
        private BigDecimal caloriesKcal;
        /** GI 值，可空；为空时 GL 规则不参与匹配（降级 UNKNOWN） */
        private BigDecimal giValue;
        /** 每 100g 碳水 g；仅由 resolver 从营养库回填，判定只用库值 */
        private BigDecimal carbsPer100g;
        /** 每 100g 升糖负荷 = giValue × carbsPer100g / 100；库未命中或缺字段时为 null */
        private BigDecimal per100gGl;
        /** GI 来源 CLIENT|LIBRARY，由 resolver 填充 */
        private String giSource;
        /** GI 可信度 LOW=客户端 GI 与库值差异过大；其余为 null */
        private String giReliability;
        /** per100gGl 为空时的缺失字段说明（如 GI、每100g碳水、食品库未匹配） */
        private List<String> missingFields;
        /** 营养来源：LIBRARY|AI_ESTIMATE|MANUAL，可空 */
        private String nutritionSource;
        /** 识别置信度 0-100，可空 */
        private Integer confidence;
        /** 用户是否手动修正过，可空；save 模式下修正项按客户端值优先处理 */
        private Boolean userCorrected;
        /** 营养库匹配状态：AVAILABLE|PENDING_REVIEW|UNRESOLVED，可空 */
        private String libraryStatus;
        /**
         * 所属菜品名，仅用于分组展示与落库，不参与判定。
         *
         * <p>为什么放在 ItemInput 而不是只在请求 DTO 上：保存链路是
         * 「请求 → ItemInput → MealNutritionResolver 归一化 → meal_food_item」，
         * 中途会被 resolver 重建成新的 ItemInput，字段不在这里就会被静默丢掉。</p>
         */
        private String dishName;
        /** MAIN|SEASONING，仅展示用，不参与判定。 */
        private String role;
        /** 烹饪方式，仅展示用，不参与判定。 */
        private String cookMethod;
    }

    /** 引擎输出：单个食物项的判定结果。 */
    @Data
    public static class ItemResult {
        private Long foodId;
        private String foodName;
        /** 最终档位（含份量修正）：LOW|MODERATE|HIGH|UNKNOWN */
        private String tier;
        /** 份量修正前档位（per100gGL 档位） */
        private String baseTier;
        /** 命中规则编码；UNKNOWN 时为 null */
        private String ruleCode;
        /** per100gGL 值（食物固有属性，稳定不随份量跳变）；缺失为 null */
        private BigDecimal per100gGl;
        /** GI 来源 CLIENT|LIBRARY */
        private String giSource;
        /** GI 可信度 LOW=输入值与库值差异过大；其余为 null */
        private String giReliability;
        /** UNKNOWN 时的缺失字段说明 */
        private List<String> missingFields;
        /** 是否“重点关注”项（最终档位 HIGH） */
        private Boolean focused;
        /** 重点关注原因：INTRINSIC=本身偏高|PORTION=份量偏大 */
        private String focusReason;
        /** 归一化后的食物大类（可能由库补全），由响应组装层回填，引擎不消费 */
        private Integer categoryCode;
        /** 归一化后的 GI（可能由库补全），由响应组装层回填，引擎不消费 */
        private BigDecimal giValue;
        /** 该份碳水 g，由响应组装层回填 */
        private BigDecimal carbsG;
        /** 营养库匹配状态 AVAILABLE|PENDING_REVIEW|UNRESOLVED，由响应组装层回填 */
        private String libraryStatus;
        /** 归一化后的营养来源 LIBRARY|AI_ESTIMATE|MANUAL|UNKNOWN，由响应组装层回填 */
        private String nutritionSource;
    }

    /** 重点关注项（单项 HIGH 的标记，只标注元凶，不改整餐档位）。 */
    @Data
    public static class FocusItem {
        private String foodName;
        /** INTRINSIC=本身偏高|PORTION=份量偏大 */
        private String reason;
        /** 展示文案（{foodName} 已替换） */
        private String text;
    }

    /** 引擎输出：整餐判定结果。 */
    @Data
    public static class Evaluation {
        private List<ItemResult> items;
        /** 整餐档位：碳水占比最大的非 UNKNOWN 项（修正后）档位；全 UNKNOWN 为 UNKNOWN */
        private String overallTier;
        /** 驱动整餐结论的规则编码；整餐 UNKNOWN 时为 null（字段名保留兼容旧契约） */
        private String worstRuleCode;
        /** 重点关注项列表（最终档位 HIGH 的项） */
        private List<FocusItem> focusItems;
    }

    /** 预览请求（POST /api/v1/meal-suitability/preview）。 */
    @Data
    public static class PreviewRequest {
        /** 餐点节点编码；仅透传，不参与判定 */
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
        /** 最终档位（含份量修正）：LOW|MODERATE|HIGH|UNKNOWN */
        private String tier;
        /** 升糖负担较低/中等/较高/信息不足 */
        private String tierLabel;
        /** 份量修正前档位（per100gGL 档位） */
        private String baseTier;
        /** 规则级单项建议（占位符已替换，可空）；为空时前端按档位兜底 */
        private String adviceText;
        /** 命中规则编码；UNKNOWN 时为 null */
        private String ruleCode;
        /** per100gGL 值（食物固有属性）；缺失为 null */
        private BigDecimal per100gGl;
        /** GI 来源 CLIENT|LIBRARY */
        private String giSource;
        /** GI 可信度 LOW=输入值与库值差异过大，提示“结果仅供参考”；其余为 null */
        private String giReliability;
        /** UNKNOWN 时的缺失字段说明 */
        private List<String> missingFields;
        /** 是否“重点关注”项 */
        private Boolean focused;
        /** 重点关注原因：INTRINSIC|PORTION */
        private String focusReason;
        /** 归一化后的食物大类（展示值与判定依据保持一致） */
        private Integer categoryCode;
        /** 归一化后的 GI（展示值与判定依据保持一致） */
        private BigDecimal giValue;
        /** 该份碳水 g */
        private BigDecimal carbsG;
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
        /** 驱动整餐结论的规则编码；整餐 UNKNOWN 时为 null（字段名保留兼容旧契约） */
        private String worstRuleCode;
        /** 重点关注项列表（单项 HIGH 的标记） */
        private List<FocusItem> focusItems;
        private List<PreviewItem> items;
        /** 免责声明（定稿待法务/营养师双签） */
        private String disclaimer;
    }
}
