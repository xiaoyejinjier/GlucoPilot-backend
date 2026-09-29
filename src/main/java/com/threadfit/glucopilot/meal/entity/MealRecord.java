package com.threadfit.glucopilot.meal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 饮食记录（meal_record，见 V1 L466-497）。
 * 营养快照保存时点固化、不可变；nodeCode 须为餐点（is_meal=1）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_record")
public class MealRecord extends BaseEntity {

    private Long userId;
    private LocalDate recordDate;
    private String nodeCode;
    private Long mediaId;
    private String foodsText;
    private BigDecimal carbsG;
    private Integer hungerLevel;
    private String supplementText;
    private BigDecimal predictNow;
    private BigDecimal predictSuggest;
    private String suggestText;
    private LocalDateTime recordedAt;
}
