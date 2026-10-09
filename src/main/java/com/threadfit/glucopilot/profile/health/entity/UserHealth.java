package com.threadfit.glucopilot.profile.health.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 健康档案实体（表 user_health），单行表、一用户一份活跃档案。
 * 字段说明：
 * - user_id            归属用户；
 * - diabetes_type      糖尿病类型 0未填/1一型/2二型/3妊娠/4其他；
 * - diabetes_type_text 类型展示文案，由服务端按 diabetes_type 派生写入；
 * - diagnosed_ym       确诊年月，CHAR(7) 格式 YYYY-MM；
 * - age                年龄；
 * - medication         用药情况；
 * - exercise_habit     运动习惯；
 * - target_fasting     空腹血糖目标，DECIMAL(4,1) 范围 1.0~33.3 mmol/L；
 * - target_postprandial 餐后血糖目标，DECIMAL(4,1) 范围 1.0~33.3 mmol/L；
 * 唯一约束 uk_user_active(user_id, is_deleted) 保证一用户一份活跃档案。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_health")
public class UserHealth extends BaseEntity {

    private Long userId;
    private Integer diabetesType;
    private String diabetesTypeText;
    private String diagnosedYm;
    private Integer age;
    private String medication;
    private String exerciseHabit;
    private BigDecimal targetFasting;
    private BigDecimal targetPostprandial;
}
