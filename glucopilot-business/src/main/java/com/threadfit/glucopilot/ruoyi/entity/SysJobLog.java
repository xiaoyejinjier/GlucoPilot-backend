package com.threadfit.glucopilot.ruoyi.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.AppendOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gp_legacy_sys_job_log")
public class SysJobLog extends AppendOnlyEntity {
    private Long jobId;
    private String jobName;
    private String jobGroup;
    private String invokeTarget;
    private String jobMessage;
    private Integer jobStatus;
    private String exceptionInfo;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
}
