package com.threadfit.glucopilot.ai.business.ocr.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.ai.business.ocr.dto.OcrDtos.CreateRequest;
import com.threadfit.glucopilot.ai.business.ocr.dto.OcrDtos.GlucoseResult;
import com.threadfit.glucopilot.ai.business.ocr.dto.OcrDtos.TaskView;
import com.threadfit.glucopilot.ai.business.ocr.entity.OcrTask;
import com.threadfit.glucopilot.ai.business.ocr.mapper.OcrTaskMapper;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.OcrBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.TaskStatus;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.idempotency.IdempotencyStore;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class OcrTaskService {

    private static final String RECOGNIZE_INSTRUCTION =
            "请读取这张血糖仪照片中的血糖数值和单位，严格按系统提示词输出 JSON。";
    private static final int RAW_TEXT_MAX_LENGTH = 128;
    private static final int ERROR_MAX_LENGTH = 512;

    private final OcrTaskMapper taskMapper;
    private final MediaFileService mediaFileService;
    private final VisionAnalyzer visionAnalyzer;
    private final JsonUtils jsonUtils;
    private final IdempotencyStore idempotencyStore;
    private final Executor taskExecutor;

    public OcrTaskService(OcrTaskMapper taskMapper,
                          MediaFileService mediaFileService,
                          VisionAnalyzer visionAnalyzer,
                          JsonUtils jsonUtils,
                          IdempotencyStore idempotencyStore,
                          @Qualifier("ocrTaskExecutor") Executor taskExecutor) {
        this.taskMapper = taskMapper;
        this.mediaFileService = mediaFileService;
        this.visionAnalyzer = visionAnalyzer;
        this.jsonUtils = jsonUtils;
        this.idempotencyStore = idempotencyStore;
        this.taskExecutor = taskExecutor;
    }

    public TaskView create(CreateRequest request, Long userId) {
        OcrBizType bizType = CodeEnum.of(OcrBizType.class, request.getBizType());
        if (bizType != OcrBizType.GLUCOSE) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "本期仅支持血糖仪读数识别");
        }

        MediaFile media = mediaFileService.findOwnedMedia(request.getMediaId(), userId, MediaBizType.GLUCOSE);
        if (media == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "血糖图片不存在或不可用");
        }

        String idempotencyKey = RequestContext.getIdempotencyKey();
        String snapshotKey = null;
        if (StringUtils.hasText(idempotencyKey)) {
            snapshotKey = IdempotencyStore.key("ocr:create", userId, idempotencyKey);
            Optional<String> snapshot = idempotencyStore.find(snapshotKey);
            if (snapshot.isPresent()) {
                return jsonUtils.fromJson(snapshot.get(), TaskView.class);
            }
        }

        OcrTask task = new OcrTask();
        task.setUserId(userId);
        task.setMediaId(media.getId());
        task.setBizType(bizType.getCode());
        task.setTaskStatus(TaskStatus.PENDING.getCode());
        taskMapper.insert(task);

        TaskView created = toView(task);
        if (snapshotKey != null) {
            idempotencyStore.saveIfAbsent(snapshotKey, created);
        }
        taskExecutor.execute(() -> recognize(task.getId(), userId, media));
        return created;
    }

    public TaskView detail(Long id, Long userId) {
        OcrTask task = taskMapper.selectOne(new LambdaQueryWrapper<OcrTask>()
                .eq(OcrTask::getId, id)
                .eq(OcrTask::getUserId, userId)
                .last("LIMIT 1"));
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "OCR 任务不存在或不属于当前用户");
        }
        return toView(task);
    }

    private void recognize(Long taskId, Long userId, MediaFile media) {
        updateStatus(taskId, userId, TaskStatus.RUNNING, null, null);
        try {
            String modelOutput = visionAnalyzer.analyze(media, SceneCode.OCR_GLUCOSE, RECOGNIZE_INSTRUCTION);
            GlucoseResult result = normalize(parseModelResult(modelOutput));
            updateStatus(taskId, userId, TaskStatus.SUCCEEDED, jsonUtils.toJson(result), null);
        } catch (RuntimeException e) {
            log.error("血糖 OCR 识别失败 taskId={} mediaId={}", taskId, media.getId(), e);
            updateStatus(taskId, userId, TaskStatus.FAILED, null, safeError(e));
        }
    }

    private GlucoseResult parseModelResult(String modelOutput) {
        if (!StringUtils.hasText(modelOutput)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "识别服务未返回有效内容");
        }
        String resultJson = modelOutput.trim();
        int begin = resultJson.indexOf('{');
        int end = resultJson.lastIndexOf('}');
        if (begin < 0 || end <= begin) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "识别结果格式不正确");
        }
        return jsonUtils.fromJson(resultJson.substring(begin, end + 1), GlucoseResult.class);
    }

    private GlucoseResult normalize(GlucoseResult result) {
        if (result == null || !Integer.valueOf(1).equals(result.getSchemaVersion())
                || !Integer.valueOf(OcrBizType.GLUCOSE.getCode()).equals(result.getBizType())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "识别结果版本或业务类型不正确");
        }
        int confidence = result.getConfidence() == null ? 0
                : Math.max(0, Math.min(100, result.getConfidence()));
        result.setConfidence(confidence);
        result.setRawText(truncate(result.getRawText(), RAW_TEXT_MAX_LENGTH));

        if (result.getSuggestedValue() != null) {
            if (result.getSuggestedValue().signum() <= 0
                    || result.getSuggestedValue().compareTo(new BigDecimal("40")) >= 0) {
                result.setSuggestedValue(null);
                result.setConfidence(Math.min(confidence, 30));
            } else {
                result.setSuggestedValue(result.getSuggestedValue().setScale(1, RoundingMode.HALF_UP));
            }
        }
        return result;
    }

    private void updateStatus(Long taskId,
                              Long userId,
                              TaskStatus status,
                              String resultJson,
                              String errorMsg) {
        OcrTask update = new OcrTask();
        update.setId(taskId);
        update.setUpdatedBy(userId);
        update.setTaskStatus(status.getCode());
        update.setOcrResultJson(resultJson);
        update.setErrorMsg(errorMsg);
        if (status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED) {
            update.setFinishedAt(LocalDateTime.now());
        }
        taskMapper.updateById(update);
    }

    private TaskView toView(OcrTask task) {
        GlucoseResult result = StringUtils.hasText(task.getOcrResultJson())
                ? jsonUtils.fromJson(task.getOcrResultJson(), GlucoseResult.class)
                : null;
        return TaskView.builder()
                .id(String.valueOf(task.getId()))
                .mediaId(String.valueOf(task.getMediaId()))
                .bizType(task.getBizType())
                .taskStatus(task.getTaskStatus())
                .ocrResult(result)
                .errorMsg(task.getErrorMsg())
                .finishedAt(task.getFinishedAt())
                .build();
    }

    private static String safeError(RuntimeException e) {
        String message = e instanceof BizException && StringUtils.hasText(e.getMessage())
                ? e.getMessage()
                : "识别服务暂时不可用，请手动输入";
        return truncate(message, ERROR_MAX_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
