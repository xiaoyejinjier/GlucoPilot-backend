package com.threadfit.glucopilot.meal.suitability.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 适食规则实体（food_suitability_rule，见 V88）。
 * <p>规则按 {@code priority} 升序匹配：GI 覆盖(5) 先于分类规则(10~30)；
 * 阈值与档位全部来自表，引擎不写死任何数值。
 * <p>1a 仅使用 {@code categoryCode} 与 {@code giMin/giMax}，
 * {@code carbsMin/carbsMax} 为阶段 2 预留，可为空。
 * <p>{@code isEnabled} 必须用 Integer：boolean 会生成 {@code isEnabled()} 形态的 getter，
 * 导致 MyBatis-Plus 把属性解析成 {@code enabled} 并映射到不存在的列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("food_suitability_rule")
public class FoodSuitabilityRule extends BaseEntity {

    /** 规则编码：GL_xxx / GI_xxx / CARB_xxx；同版本内唯一 */
    private String ruleCode;
    /** 判定指标：GL=每100g升糖负荷|GI=升糖指数|CARB=每100g碳水；旧行 NULL 按 GI 解释 */
    private String metric;
    /** 规则作用域：ITEM=单项|MEAL=整餐；旧行 NULL 按 ITEM 解释 */
    private String scope;
    /** 食物大类 1-9；为空表示不限分类 */
    private Integer categoryCode;
    /** GI 下限（含），可空表示不限 */
    private BigDecimal giMin;
    /** GI 上限（含），可空表示不限 */
    private BigDecimal giMax;
    /** per100gGL 下限（含），可空表示不限；仅 metric=GL 时生效 */
    private BigDecimal glMin;
    /** per100gGL 上限（含），可空表示不限；仅 metric=GL 时生效 */
    private BigDecimal glMax;
    /** 每 100g 碳水下限（含）g，可空表示不限 */
    private BigDecimal carbsMin;
    /** 每 100g 碳水上限（含）g，可空表示不限 */
    private BigDecimal carbsMax;
    /** 判定档位：LOW|MODERATE|HIGH */
    private String tier;
    /** 单项建议文案（规则级，不含食物名，可空）；为空时由服务端按档位兜底 */
    private String adviceText;
    /** 匹配优先级，升序优先 */
    private Integer priority;
    /** 规则版本：2026.11.0-placeholder（占位）/ 2026.11.1（正式 V1） */
    private String ruleVersion;
    /** 是否启用 0否1是 */
    private Integer isEnabled;
}
