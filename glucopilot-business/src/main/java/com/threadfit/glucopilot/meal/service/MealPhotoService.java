package com.threadfit.glucopilot.meal.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import com.threadfit.glucopilot.meal.mapper.MealPhotoMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MealPhotoService {
    private final MealPhotoMapper mapper;
    private final ObjectStorageService storage;

    public record Image(byte[] bytes, String mime) {}

    public static String path(long id) {
        return "/api/v1/meal-records/images/" + id;
    }

    public Image read(long user, long id) {
        var row = mapper.selectOwnedPhoto(user, id);
        if (row == null) {
            throw missing();
        }
        String mime = row.getMimeType();
        String key = row.getStorageKey();
        Integer provider = row.getStorageProvider();
        Long size = row.getSizeBytes();
        if (!MediaFileConstant.isMimeAllowed(MediaBizType.MEAL, mime)
                || key == null
                || key.isBlank()
                || provider == null
                || provider != storage.provider().getCode()
                || !Objects.equals(row.getBucketName(), storage.bucket())
                || size == null
                || size <= 0
                || size > MediaFileConstant.DEFAULT_MAX_SIZE_BYTES) {
            throw missing();
        }
        byte[] bytes = storage.getObject(key);
        if (bytes == null
                || bytes.length == 0
                || bytes.length > MediaFileConstant.DEFAULT_MAX_SIZE_BYTES) {
            throw missing();
        }
        return new Image(bytes, mime);
    }

    private static BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "正餐照片不存在或不可查看");
    }
}
