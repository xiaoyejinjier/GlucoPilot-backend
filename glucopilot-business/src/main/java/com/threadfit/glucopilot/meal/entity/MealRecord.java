package com.threadfit.glucopilot.meal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 饮食记录主表实体（meal_record，见 V1 L466-497）。
 * <p>营养快照保存时点固化、不可变；nodeCode 须为餐点（is_meal=1）。
 * 覆盖语义依赖 (user_id, record_date, node_code, is_deleted) 唯一键；预测三列仅落库不重算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_record")
public class MealRecord extends BaseEntity {

    private Long userId;
    private LocalDate recordDate;
    private String nodeCode;
    /** 餐食照片 ID（file 侧关联），可空 */
    private Long mediaId;
    /** 食物简述快照 */
    private String foodsText;
    /** 碳水克数快照 */
    private BigDecimal carbsG;
    /** 饥饿感：1饱腹 2一般 3微饿，可空 */
    private Integer hungerLevel;
    /** 补餐备注，可空 */
    private String supplementText;
    /** 预测当前血糖（mmol/L），可空 */
    private BigDecimal predictNow;
    /** 预测建议血糖（mmol/L），可空 */
    private BigDecimal predictSuggest;
    /** 预测建议文案，可空 */
    private String suggestText;
    /** 记录时刻（服务端写入，防伪） */
    private LocalDateTime recordedAt;
}
