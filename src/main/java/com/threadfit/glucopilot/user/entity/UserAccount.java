package com.threadfit.glucopilot.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_account")
public class UserAccount extends BaseEntity {

    private String username;
    private String openid;
    private String unionId;
    private String phone;
    private Integer accountStatus;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
}
