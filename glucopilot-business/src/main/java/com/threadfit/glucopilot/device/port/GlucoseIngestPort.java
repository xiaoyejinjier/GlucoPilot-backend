package com.threadfit.glucopilot.device.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 血糖写入端口（归属 device）：Mock 设备 / 后续手动录入共用，避免 Adapter 直耦 Mapper。
 */
public interface GlucoseIngestPort {

    record IngestCommand(
            Long userId,
            LocalDate recordDate,
            String nodeCode,
            BigDecimal glucoseMmolL,
            int sourceType,
            String remark,
            LocalDateTime recordedAt,
            /** 设备侧外部记录 ID，写入 remark 备注 */
            String externalKey
    ) {
    }

    record IngestResult(Long recordId, boolean created) {
    }

    IngestResult upsertByNode(IngestCommand command);
}
