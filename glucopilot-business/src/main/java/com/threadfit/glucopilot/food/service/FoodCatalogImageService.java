package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 管理员审核上架的食品才可通过食品ID展示来源图，原健康照片接口保持私有。 */
@Service
@RequiredArgsConstructor
public class FoodCatalogImageService {
    private final FoodNutritionMapper foods;
    private final FoodCategoryMapper categories;
    private final FoodReviewImageService photos;

    public static String imageUrl(FoodNutrition food) {
        if (FoodImageService.isUploadedFoodImage(food.getImageUrl())) return food.getImageUrl();
        return publishable(food) ? "/api/v1/foods/" + food.getId() + "/image" : food.getImageUrl();
    }

    public FoodReviewImageService.Image read(Long foodId) {
        var food = foods.selectById(foodId);
        if (!publishable(food) || food.getCategoryId() == null) throw missing();
        var category = categories.selectById(food.getCategoryId());
        if (category == null || Integer.valueOf(1).equals(category.getIsDeleted())
                || !Integer.valueOf(1).equals(category.getIsEnabled())) throw missing();
        return photos.read(foodId);
    }

    private static boolean publishable(FoodNutrition food) {
        return FoodReviewImageService.hasSourcePhoto(food) && food.getId() != null
                && "AI_REVIEWED".equals(food.getNutritionSource())
                && Integer.valueOf(1).equals(food.getShelfStatus());
    }

    private static BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "食物图片不存在或已下架");
    }
}
