package com.threadfit.glucopilot.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.idempotency.IdempotencyStore;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.dto.FileDtos.RegisterRequest;
import com.threadfit.glucopilot.file.dto.FileDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import com.threadfit.glucopilot.file.vo.FileVos.MediaFileRegisterResult;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MediaFileServiceImpl implements MediaFileService {

    private static final int FILE_STATUS_AVAILABLE = 1;

    private final MediaFileMapper mediaFileMapper;
    private final ObjectStorageService objectStorageService;
    private final IdempotencyStore idempotencyStore;
    private final JsonUtils jsonUtils;

    @Override
    public UploadCredential createUploadCredential(UploadCredentialRequest request, Long userId) {
        MediaBizType bizType = CodeEnum.of(MediaBizType.class, request.getBizType());
        validateSizeAndMime(bizType, request.getMimeType(), request.getSizeBytes());
        String ext = MediaFileConstant.extOf(request.getMimeType());
        return objectStorageService.createUploadCredential(bizType, ext, request.getSizeBytes(), userId);
    }

    @Override
    @Transactional
    public MediaFileRegisterResult register(RegisterRequest request, Long userId) {
        MediaBizType bizType = CodeEnum.of(MediaBizType.class, request.getBizType());
        validateSizeAndMime(bizType, request.getMimeType(), request.getSizeBytes());

        // 1. 强校验 storageKey 前缀含 userId（防伪造）
        requireOwnStorageKey(request.getStorageKey(), bizType, userId);

        // 2. HEAD 确认对象已存在
        if (!objectStorageService.objectExists(request.getStorageKey())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "对象尚未上传");
        }

        // 3. 内容级去重（sha256Hex + userId + bizType），命中复用
        Optional<MediaFile> duplicated = findDuplicated(userId, bizType, request.getSha256Hex());
        if (duplicated.isPresent()) {
            return toResult(duplicated.get());
        }

        // 4. 接口级幂等：X-Idempotency-Key
        String idempotencyKey = RequestContext.getIdempotencyKey();
        if (idempotencyKey != null) {
            String key = IdempotencyStore.key("media:register", userId, idempotencyKey);
            Optional<String> snapshot = idempotencyStore.find(key);
            if (snapshot.isPresent()) {
                return jsonUtils.fromJson(snapshot.get(), MediaFileRegisterResult.class);
            }
            MediaFile saved = insertMediaFile(bizType, request, userId);
            MediaFileRegisterResult result = toResult(saved);
            idempotencyStore.saveIfAbsent(key, result);
            return result;
        }

        return toResult(insertMediaFile(bizType, request, userId));
    }

    @Override
    public MediaFile findAvailableAvatar(Long userId, String fileUrl) {
        return mediaFileMapper.selectOne(new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getUserId, userId)
                .eq(MediaFile::getBizType, MediaBizType.AVATAR.getCode())
                .eq(MediaFile::getFileUrl, fileUrl)
                .eq(MediaFile::getFileStatus, FILE_STATUS_AVAILABLE)
                .last("LIMIT 1"));
    }

    @Override
    public MediaFile findOwnedMedia(Long mediaId, Long userId, MediaBizType bizType) {
        return mediaFileMapper.selectOne(new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaId)
                .eq(MediaFile::getUserId, userId)
                .eq(MediaFile::getBizType, bizType.getCode())
                .eq(MediaFile::getFileStatus, FILE_STATUS_AVAILABLE)
                .last("LIMIT 1"));
    }

    private MediaFile insertMediaFile(MediaBizType bizType, RegisterRequest request, Long userId) {
        StorageProvider provider = objectStorageService.provider();
        MediaFile row = new MediaFile();
        row.setUserId(userId);
        row.setBizType(bizType.getCode());
        row.setStorageProvider(provider.getCode());
        row.setStorageKey(request.getStorageKey());
        row.setMimeType(request.getMimeType());
        row.setSizeBytes(request.getSizeBytes());
        row.setSha256Hex(request.getSha256Hex());
        row.setFileStatus(FILE_STATUS_AVAILABLE);
        row.setFileUrl(objectStorageService.resolvePublicUrl(request.getStorageKey()));
        mediaFileMapper.insert(row);
        return row;
    }

    private Optional<MediaFile> findDuplicated(Long userId, MediaBizType bizType, String sha256Hex) {
        if (sha256Hex == null || sha256Hex.isBlank()) {
            return Optional.empty();
        }
        MediaFile row = mediaFileMapper.selectOne(new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getUserId, userId)
                .eq(MediaFile::getBizType, bizType.getCode())
                .eq(MediaFile::getSha256Hex, sha256Hex)
                .last("LIMIT 1"));
        return Optional.ofNullable(row);
    }

    /** storageKey = {bizType}/{yyyy}/{MM}/{userId}/{uuid}.{ext}，前缀必须含 /{userId}/ 且 bizType 段一致。 */
    private void requireOwnStorageKey(String storageKey, MediaBizType bizType, Long userId) {
        String[] parts = storageKey.split("/");
        if (parts.length < 5) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "storageKey 非法");
        }
        if (!String.valueOf(bizType.getCode()).equals(parts[0])) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "storageKey 业务类型不匹配");
        }
        if (!String.valueOf(userId).equals(parts[3])) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "storageKey 不属于当前用户");
        }
    }

    private void validateSizeAndMime(MediaBizType bizType, String mimeType, long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > MediaFileConstant.DEFAULT_MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件大小超出限制");
        }
        if (!MediaFileConstant.isMimeAllowed(bizType, mimeType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的 MIME 类型");
        }
    }

    private MediaFileRegisterResult toResult(MediaFile row) {
        return MediaFileRegisterResult.builder()
                .id(String.valueOf(row.getId()))
                .fileUrl(row.getFileUrl())
                .build();
    }
}