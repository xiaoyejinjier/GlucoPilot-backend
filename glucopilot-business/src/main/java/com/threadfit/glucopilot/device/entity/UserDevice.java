package com.threadfit.glucopilot.device.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_device")
public class UserDevice extends BaseEntity {

    private String deviceSn;
    private String deviceName;
    private String provider;
    private Long userId;
    private Integer deviceStatus;
    private LocalDateTime lastSyncAt;
}
