package com.threadfit.glucopilot.glucose.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.glucose.port.GlucoseIngestPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class GlucoseIngestService implements GlucoseIngestPort {

    private final GlucoseRecordMapper glucoseRecordMapper;

    @Override
    @Transactional
    public IngestResult upsertByNode(IngestCommand command) {
        if (command.userId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!GlucoseNodes.isGlucoseNode(command.nodeCode())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "nodeCode 必须是非餐点血糖节点");
        }
        BigDecimal value = command.glucoseMmolL();
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(new BigDecimal("40")) >= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "血糖值不合法");
        }

        GlucoseRecord existing = glucoseRecordMapper.selectOne(new LambdaQueryWrapper<GlucoseRecord>()
                .eq(GlucoseRecord::getUserId, command.userId())
                .eq(GlucoseRecord::getRecordDate, command.recordDate())
                .eq(GlucoseRecord::getNodeCode, command.nodeCode())
                .last("LIMIT 1"));

        String remark = command.remark();
        if (StringUtils.hasText(command.externalKey())) {
            remark = (remark == null ? "" : remark + " ") + "[ext:" + command.externalKey() + "]";
        }

        if (existing == null) {
            GlucoseRecord row = new GlucoseRecord();
            row.setUserId(command.userId());
            row.setRecordDate(command.recordDate());
            row.setNodeCode(command.nodeCode());
            row.setGlucoseValue(value);
            row.setSourceType(command.sourceType());
            row.setRemark(trimRemark(remark));
            row.setRecordedAt(command.recordedAt() == null ? LocalDateTime.now() : command.recordedAt());
            glucoseRecordMapper.insert(row);
            return new IngestResult(row.getId(), true);
        }

        existing.setGlucoseValue(value);
        existing.setSourceType(command.sourceType());
        existing.setRemark(trimRemark(remark));
        existing.setRecordedAt(command.recordedAt() == null ? LocalDateTime.now() : command.recordedAt());
        glucoseRecordMapper.updateById(existing);
        return new IngestResult(existing.getId(), false);
    }

    private static String trimRemark(String remark) {
        if (remark == null) {
            return null;
        }
        return remark.length() <= 256 ? remark : remark.substring(0, 256);
    }
}
