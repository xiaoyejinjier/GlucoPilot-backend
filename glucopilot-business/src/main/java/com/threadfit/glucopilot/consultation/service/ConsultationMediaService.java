package com.threadfit.glucopilot.consultation.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ConsultationMediaService {
    private final ConsultationService consultations;
    private final ConsultationMapper mapper;
    private final ObjectStorageService storage;

    public record Image(byte[] bytes, String mime) {}

    public Image read(long viewer, Long caseId, long mediaId) {
        var photo = mapper.image(mediaId);
        if (photo == null) throw missing();
        if (caseId == null) {
            if (!Objects.equals(photo.getUserId(), viewer)) throw missing();
        } else if (!consultations.detail(viewer, caseId).detailsVisible()
                || !mapper.imageIds(caseId).contains(mediaId)) throw missing();
        if (photo.getOpsId() != null
                || !Integer.valueOf(9).equals(photo.getBizType())
                || !Objects.equals(photo.getStorageProvider(), storage.provider().getCode())
                || !Objects.equals(photo.getBucketName(), storage.bucket())
                || !MediaFileConstant.isMimeAllowed(
                        MediaBizType.CONSULTATION_IMAGE, photo.getMimeType())
                || photo.getSizeBytes() == null
                || photo.getSizeBytes() < 1
                || photo.getSizeBytes() > 5242880
                || photo.getStorageKey() == null
                || photo.getStorageKey().isBlank()) throw missing();
        byte[] bytes = storage.getObject(photo.getStorageKey());
        if (bytes == null || bytes.length != photo.getSizeBytes() || bytes.length > 5242880)
            throw missing();
        return new Image(bytes, photo.getMimeType());
    }

    private BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "问诊图片不存在或无权查看");
    }
}
