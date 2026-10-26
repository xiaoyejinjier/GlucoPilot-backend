package com.threadfit.glucopilot.device.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device_sync_log")
public class DeviceSyncLog extends BaseEntity {

    private Long userId;
    private Long deviceId;
    private String provider;
    private Integer sampleCount;
    private Integer createdCount;
    private String remark;
}
