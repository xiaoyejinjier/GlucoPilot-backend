package com.threadfit.glucopilot.meal.suitability.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 份量修正规则实体（meal_portion_adjust_rule，见 V94）。
 * <p>二维映射：基础档位 × 该份碳水克数量级桶 → 修正档位。
 * 桶边界：{@code carbBucketMin} 含、{@code carbBucketMax} 不含，NULL 表示不限。
 * <p>只做温和修正：MODERATE 可上下挪一档；HIGH 不因份量降档；
 * LOW 默认不升档（预留行 enabled=0，启用需产品确认）。
 * <p>{@code isEnabled} 必须用 Integer（同 {@link FoodSuitabilityRule}，避免 MyBatis-Plus 属性名解析问题）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_portion_adjust_rule")
public class MealPortionAdjustRule extends BaseEntity {

    /** 修正前档位：LOW|MODERATE|HIGH|UNKNOWN */
    private String baseTier;
    /** 该份碳水克数下限（含），NULL=不限 */
    private BigDecimal carbBucketMin;
    /** 该份碳水克数上限（不含），NULL=不限 */
    private BigDecimal carbBucketMax;
    /** 修正后档位：LOW|MODERATE|HIGH */
    private String adjustedTier;
    /** 同桶多行时升序取首个 */
    private Integer priority;
    /** 是否启用 0否1是 */
    private Integer isEnabled;
}
