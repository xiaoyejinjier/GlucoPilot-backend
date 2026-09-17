package com.threadfit.glucopilot.glucose.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 血糖写入端口：device / OCR / 手动 共用，避免跨模块直耦 Mapper。
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
            /** 设备侧幂等键：同用户同日同节点覆盖写入时的外部备注（当前 DDL 无独立列，写入 remark） */
            String externalKey
    ) {
    }

    record IngestResult(Long recordId, boolean created) {
    }

    IngestResult upsertByNode(IngestCommand command);
}
