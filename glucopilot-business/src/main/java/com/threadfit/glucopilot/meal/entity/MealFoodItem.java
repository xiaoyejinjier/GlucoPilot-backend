package com.threadfit.glucopilot.meal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 饮食明细实体（meal_food_item，见 V1 L499-523）。
 * 隶属于主记录（mealId）；每次保存按旧删新建重建，sortNo 为顺序序号。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_food_item")
public class MealFoodItem extends BaseEntity {

    private Long mealId;
    /** 营养库食物 ID，可空 = 手输 */
    private Long foodId;
    /** 食物名称快照（必填） */
    private String foodName;
    /** 克重快照 */
    private BigDecimal weightG;
    /** 碳水克数快照 */
    private BigDecimal carbsG;
    /** 热量（kcal）快照 */
    private BigDecimal caloriesKcal;
    /** 明细顺序（0 起） */
    private Integer sortNo;
    /** 营养库待审行 ID（AI 估算在 food_nutrition 的草稿行），可空 */
    private Long libraryFoodId;
    /** 食物大类 1-9，可空 */
    private Integer categoryCode;
    /** GI 快照，可空 */
    private BigDecimal giValue;
    /** GI 来源：LIBRARY|CATEGORY_FALLBACK|NONE，可空 */
    private String giSource;
    /** 营养来源：LIBRARY|AI_ESTIMATE|MANUAL|UNKNOWN，可空 */
    private String nutritionSource;
    /** 识别时营养库匹配状态：AVAILABLE|PENDING_REVIEW|UNRESOLVED，可空（V90，3b2 起写入） */
    private String libraryStatus;
    /** 识别置信度 0-100，可空 */
    private Integer confidence;
    /** 是否人工修正：0否1是，可空；用 Integer 而非 boolean，避免 MyBatis-Plus 属性名解析问题 */
    private Integer userCorrected;
    /** 份量倍数，默认 1.00 */
    private BigDecimal portionRatio;
    /** 单项结论档位 LOW|MODERATE|HIGH|UNKNOWN，可空 */
    private String adviceTier;
    /** 单项规则原因码，可空 */
    private String adviceReasonCode;
    /** 单项通用建议文案，可空 */
    private String adviceText;
}
