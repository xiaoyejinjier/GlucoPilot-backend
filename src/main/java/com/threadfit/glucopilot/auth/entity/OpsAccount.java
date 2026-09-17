package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_account")
public class OpsAccount extends BaseEntity {

    private String username;
    private String passwordHash;
    private Integer accountStatus;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
}
