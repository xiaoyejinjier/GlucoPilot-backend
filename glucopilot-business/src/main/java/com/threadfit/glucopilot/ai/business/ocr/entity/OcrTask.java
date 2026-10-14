package com.threadfit.glucopilot.ai.business.ocr.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ocr_task")
public class OcrTask extends BaseEntity {
    private Long userId;
    private Long mediaId;
    private Integer bizType;
    private Integer taskStatus;
    private String ocrResultJson;
    private String errorMsg;
    private LocalDateTime finishedAt;
}
