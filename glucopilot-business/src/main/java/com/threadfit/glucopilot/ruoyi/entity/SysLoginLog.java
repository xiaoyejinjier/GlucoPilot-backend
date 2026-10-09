package com.threadfit.glucopilot.ruoyi.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.AppendOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gp_legacy_sys_login_log")
public class SysLoginLog extends AppendOnlyEntity {
    private String username;
    private Integer loginStatus;
    private String loginMessage;
    private String clientIp;
    private String userAgent;
    private LocalDateTime loginAt;
}
