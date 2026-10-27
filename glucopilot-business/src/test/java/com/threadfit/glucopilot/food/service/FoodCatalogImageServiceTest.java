package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.food.controller.FoodCatalogImageController;
import com.threadfit.glucopilot.food.entity.FoodCategory;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@ExtendWith(MockitoExtension.class)
class FoodCatalogImageServiceTest {
    @Mock FoodNutritionMapper foods;
    @Mock FoodCategoryMapper categories;
    @Mock FoodReviewImageService photos;
    @InjectMocks FoodCatalogImageService service;

    @Test void approvedPublishedFoodUsesSamePhotographAsAdminWithoutPublicMediaId() {
        var food = published();
        when(foods.selectById(7L)).thenReturn(food);
        when(categories.selectById(2L)).thenReturn(category());
        var photo = new FoodReviewImageService.Image(new byte[]{1, 2, 3}, "image/jpeg");
        when(photos.read(7L)).thenReturn(photo);
        assertSame(photo, service.read(7L));
        assertEquals("/api/v1/foods/7/image", FoodCatalogImageService.imageUrl(food));
        assertFalse(FoodCatalogImageService.imageUrl(food).contains("88"));
    }

    @Test void nonPublishedOrUnapprovedFoodsCannotExposeSourceHealthPhotos() {
        var food = published();
        when(foods.selectById(7L)).thenReturn(food);
        food.setShelfStatus(0);
        assertThrows(BizException.class, () -> service.read(7L));
        food.setShelfStatus(1); food.setNutritionSource("AI_ESTIMATE");
        assertThrows(BizException.class, () -> service.read(7L));
        food.setNutritionSource("MANUAL");
        assertThrows(BizException.class, () -> service.read(7L));
        food.setNutritionSource("AI_REVIEWED"); food.setIsDeleted(1);
        assertThrows(BizException.class, () -> service.read(7L));
        food.setIsDeleted(0); food.setSourceMediaId(null);
        assertThrows(BizException.class, () -> service.read(7L));
        when(foods.selectById(7L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.read(7L));
        verifyNoInteractions(categories, photos);
    }

    @Test void disabledDeletedOrMissingCategoryRevokesImageImmediately() {
        when(foods.selectById(7L)).thenReturn(published());
        var category = category();
        when(categories.selectById(2L)).thenReturn(category);
        category.setIsEnabled(0);
        assertThrows(BizException.class, () -> service.read(7L));
        category.setIsEnabled(1); category.setIsDeleted(1);
        assertThrows(BizException.class, () -> service.read(7L));
        when(categories.selectById(2L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.read(7L));
        verifyNoInteractions(photos);
    }

    @Test void missingSourceDoesNotReturnUnrelatedPictureBytes() {
        when(foods.selectById(7L)).thenReturn(published());
        when(categories.selectById(2L)).thenReturn(category());
        when(photos.read(7L)).thenThrow(new BizException(com.threadfit.glucopilot.common.api.ErrorCode.NOT_FOUND, "missing"));
        assertThrows(BizException.class, () -> service.read(7L));
    }

    @Test void foodWithoutSourceRetainsAdministratorsSelectedImage() {
        var food = published(); food.setSourceMediaId(null);
        food.setImageUrl("/api/v1/foods/images/36");
        assertEquals("/api/v1/foods/images/36", FoodCatalogImageService.imageUrl(food));
    }

    @Test void administratorsUploadedImageWinsOverRetainedRecognitionSource() {
        var food = published();
        food.setImageUrl("/api/v1/foods/images/72");
        assertEquals("/api/v1/foods/images/72", FoodCatalogImageService.imageUrl(food));
        assertEquals(88L, food.getSourceMediaId());
        food.setImageUrl("/images/foods/rice.svg");
        assertEquals("/api/v1/foods/7/image", FoodCatalogImageService.imageUrl(food));
    }

    @Test void publishedPhotoHttpResponseIsBinaryAndNeverCached() throws Exception {
        when(foods.selectById(7L)).thenReturn(published());
        when(categories.selectById(2L)).thenReturn(category());
        when(photos.read(7L)).thenReturn(new FoodReviewImageService.Image(new byte[]{1, 2, 3}, "image/jpeg"));
        var response = MockMvcBuilders.standaloneSetup(new FoodCatalogImageController(service)).build()
                .perform(get("/api/v1/foods/7/image")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("image/jpeg", response.getContentType());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertArrayEquals(new byte[]{1, 2, 3}, response.getContentAsByteArray());
    }

    private static FoodNutrition published() {
        var food = new FoodNutrition(); food.setId(7L); food.setCategoryId(2L);
        food.setSourceMediaId(88L); food.setNutritionSource("AI_REVIEWED"); food.setShelfStatus(1);
        return food;
    }

    private static FoodCategory category() {
        var category = new FoodCategory(); category.setId(2L); category.setIsEnabled(1); return category;
    }
}
