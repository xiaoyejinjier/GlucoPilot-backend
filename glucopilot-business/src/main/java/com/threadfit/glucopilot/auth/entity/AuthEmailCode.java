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
    /** 发送场景 login/bind/cancel/recover，消费时必须匹配。 */
    private String scene;
    private LocalDateTime expireAt;
    private Integer isUsed;
    private String clientIp;
}
