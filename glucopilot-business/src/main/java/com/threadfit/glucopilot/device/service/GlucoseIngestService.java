package com.threadfit.glucopilot.device.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.port.GlucoseIngestPort;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备血糖写入端口实现：只做来源校验与参数转交，
 * 「按 (recordDate, nodeCode) 覆盖写入」的唯一实现留在 glucose 模块，避免两处唯一键语义漂移。
 */
@Service
@RequiredArgsConstructor
public class GlucoseIngestService implements GlucoseIngestPort {

    private final GlucoseRecordService glucoseRecordService;

    @Override
    @Transactional
    public IngestResult upsertByNode(IngestCommand command) {
        if (command.userId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        GlucoseDtos.UpsertResult result = glucoseRecordService.upsertByNode(
                command.userId(),
                command.recordDate(),
                command.nodeCode(),
                command.glucoseMmolL(),
                command.sourceType(),
                command.remark(),
                // 设备来源不带照片，mediaId 恒空
                null,
                command.recordedAt(),
                command.externalKey());
        return new IngestResult(Long.valueOf(result.getId()), Boolean.TRUE.equals(result.getCreated()));
    }
}
