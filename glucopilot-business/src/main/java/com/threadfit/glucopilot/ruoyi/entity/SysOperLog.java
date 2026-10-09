package com.threadfit.glucopilot.ruoyi.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.AppendOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gp_legacy_sys_oper_log")
public class SysOperLog extends AppendOnlyEntity {
    private Long opsId;
    private String title;
    private String businessType;
    private String methodName;
    private String requestMethod;
    private String operatorName;
    private String requestUrl;
    private String clientIp;
    private String requestParam;
    private String responseResult;
    private Integer operStatus;
    private String errorMessage;
    private LocalDateTime operAt;
}
