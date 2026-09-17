package com.threadfit.glucopilot.device.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("glucose_record")
public class GlucoseRecord extends BaseEntity {

    private Long userId;
    private LocalDate recordDate;
    private String nodeCode;
    private BigDecimal glucoseValue;
    private Integer sourceType;
    private Long mediaId;
    private String remark;
    private LocalDateTime recordedAt;
}
