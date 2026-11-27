package com.threadfit.glucopilot.device.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device_sync_sample")
public class DeviceSyncSample extends BaseEntity {

    private Long syncLogId;
    private Long userId;
    private Long deviceId;
    private LocalDateTime recordedAt;
    private String nodeCode;
    private BigDecimal glucoseValue;
}
