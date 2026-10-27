package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 向有食品管理权限的运营持续展示识别来源照片，审核/上下架不改变图片。 */
@Service
@RequiredArgsConstructor
public class FoodReviewImageService {
    private final FoodNutritionMapper foods;
    private final MediaFileMapper media;
    private final ObjectStorageService storage;

    public Image read(Long foodId) {
        FoodNutrition food = foods.selectById(foodId);
        if (!hasSourcePhoto(food)) throw missing();
        var photo = media.selectById(food.getSourceMediaId());
        if (photo == null || Integer.valueOf(1).equals(photo.getIsDeleted())
                || photo.getUserId() == null || photo.getOpsId() != null
                || !Objects.equals(photo.getBizType(), MediaBizType.MEAL.getCode())
                || !Integer.valueOf(1).equals(photo.getFileStatus())
                || !Objects.equals(photo.getStorageProvider(), storage.provider().getCode())
                || !MediaFileConstant.isMimeAllowed(MediaBizType.MEAL, photo.getMimeType())
                || photo.getSizeBytes() == null || photo.getSizeBytes() <= 0
                || photo.getSizeBytes() > MediaFileConstant.DEFAULT_MAX_SIZE_BYTES
                || photo.getStorageKey() == null || photo.getStorageKey().isBlank()) throw missing();
        byte[] bytes = storage.getObject(photo.getStorageKey());
        if (bytes == null || bytes.length == 0 || bytes.length > MediaFileConstant.DEFAULT_MAX_SIZE_BYTES) throw missing();
        return new Image(bytes, photo.getMimeType());
    }

    public static boolean hasSourcePhoto(FoodNutrition food) {
        return food != null && !Integer.valueOf(1).equals(food.getIsDeleted())
                && food.getSourceMediaId() != null
                && ("AI_ESTIMATE".equals(food.getNutritionSource()) || "AI_REVIEWED".equals(food.getNutritionSource()));
    }

    private static BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "食品实拍图不存在或已失效");
    }

    public record Image(byte[] bytes, String mimeType) {}
}
