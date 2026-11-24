package com.threadfit.glucopilot.meal.suitability.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 适食判定配置实体（meal_suitability_config，见 V94）。
 * <p>聚合参数与文案候选统一落表：碳水占比门槛、UNKNOWN 跳过策略、
 * 档位兜底建议候选池（| 分隔）、重点关注文案。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_suitability_config")
public class MealSuitabilityConfig extends BaseEntity {

    /** 配置键 */
    private String configKey;
    /** 配置值；文案池用 | 分隔多个候选 */
    private String configValue;
    /** 说明 */
    private String remark;
}
