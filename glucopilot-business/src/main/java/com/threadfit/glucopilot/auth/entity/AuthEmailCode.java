package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("auth_email_code")
public class AuthEmailCode extends BaseEntity {

    private String email;
    private String verifyCode;
    private LocalDateTime expireAt;
    private Integer isUsed;
    private String clientIp;
}
