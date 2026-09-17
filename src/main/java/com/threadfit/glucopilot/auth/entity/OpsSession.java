package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_session")
public class OpsSession extends BaseEntity {

    private Long opsId;
    private String accessToken;
    private LocalDateTime expireAt;
    private String clientIp;
    private String userAgent;
    private Integer sessionStatus;
}
