package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 全局提醒仅提供本人审核状态和版本，不投影资质或审核备注。 */
@Data
public class DoctorReviewNotice {
    private String version;
    private String status;
    private LocalDateTime reviewedAt;
}
