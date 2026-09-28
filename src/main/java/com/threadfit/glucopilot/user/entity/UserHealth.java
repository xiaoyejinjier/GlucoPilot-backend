package com.threadfit.glucopilot.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

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
