package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_session")
public class UserSession extends BaseEntity {

    private Long userId;
    private String accessToken;
    private Integer loginType;
    private LocalDateTime expireAt;
    private String clientIp;
    private String userAgent;
    private Integer isValid;
}
