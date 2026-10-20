package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodReviewImageServiceTest {
    @Mock FoodNutritionMapper foods;
    @Mock MediaFileMapper media;
    @Mock ObjectStorageService storage;
    @InjectMocks FoodReviewImageService service;

    @Test void readsOnlyThePhotosAssociatedWithPendingFood() {
        when(foods.selectById(7L)).thenReturn(draft());
        when(media.selectById(88L)).thenReturn(photo());
        when(storage.provider()).thenReturn(StorageProvider.MINIO);
        when(storage.getObject("meal-photo")).thenReturn(new byte[]{1, 2, 3});
        var image = service.read(7L);
        assertArrayEquals(new byte[]{1, 2, 3}, image.bytes());
        assertEquals("image/jpeg", image.mimeType());
    }

    @Test void reviewedManualDeletedOrUnassociatedFoodCannotExposePhoto() {
        FoodNutrition food = draft();
        when(foods.selectById(7L)).thenReturn(food);
        food.setShelfStatus(1); assertThrows(BizException.class, () -> service.read(7L));
        food.setShelfStatus(0); food.setNutritionSource("AI_REVIEWED");
        assertThrows(BizException.class, () -> service.read(7L));
        food.setNutritionSource("MANUAL"); assertThrows(BizException.class, () -> service.read(7L));
        food.setNutritionSource("AI_ESTIMATE"); food.setIsDeleted(1);
        assertThrows(BizException.class, () -> service.read(7L));
        food.setIsDeleted(0); food.setSourceMediaId(null);
        assertThrows(BizException.class, () -> service.read(7L));
        verifyNoInteractions(media, storage);
    }

    @Test void invalidMediaIsRejectedBeforeReadingStorage() {
        when(foods.selectById(7L)).thenReturn(draft());
        MediaFile photo = photo();
        when(media.selectById(88L)).thenReturn(photo);
        photo.setBizType(1); assertThrows(BizException.class, () -> service.read(7L));
        photo.setBizType(3); photo.setFileStatus(0); assertThrows(BizException.class, () -> service.read(7L));
        photo.setFileStatus(1); photo.setIsDeleted(1); assertThrows(BizException.class, () -> service.read(7L));
        photo.setIsDeleted(0); photo.setUserId(null); assertThrows(BizException.class, () -> service.read(7L));
        verify(storage, never()).getObject(any());
    }

    private FoodNutrition draft() {
        FoodNutrition food = new FoodNutrition(); food.setId(7L); food.setSourceMediaId(88L);
        food.setNutritionSource("AI_ESTIMATE"); food.setShelfStatus(0); return food;
    }
    private MediaFile photo() {
        MediaFile photo = new MediaFile(); photo.setId(88L); photo.setUserId(1L); photo.setBizType(3);
        photo.setFileStatus(1); photo.setStorageProvider(1); photo.setMimeType("image/jpeg");
        photo.setSizeBytes(3L); photo.setStorageKey("meal-photo"); return photo;
    }
}
