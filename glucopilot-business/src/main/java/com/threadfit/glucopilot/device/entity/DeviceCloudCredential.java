package com.threadfit.glucopilot.device.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device_cloud_credential")
public class DeviceCloudCredential extends BaseEntity {

    private Long userId;

    /** 解绑/撤销关联时允许写 NULL */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long deviceId;

    private String provider;
    private String vendorSubject;
    private String accessTokenEnc;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String refreshTokenEnc;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime tokenExpiresAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String scopes;

    /** 1 有效 / 0 撤销 */
    private Integer status;
}
