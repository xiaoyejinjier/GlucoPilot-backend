package com.threadfit.glucopilot.device.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 厂商适配器输出的统一血糖样本（已换算为 mmol/L，nodeCode 为血糖节点）。
 */
public record UnifiedGlucoseSample(
        LocalDate recordDate,
        String nodeCode,
        BigDecimal glucoseMmolL,
        LocalDateTime recordedAt,
        String externalId
) {
}
