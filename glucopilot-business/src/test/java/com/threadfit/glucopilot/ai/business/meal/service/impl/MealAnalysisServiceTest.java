package com.threadfit.glucopilot.ai.business.meal.service.impl;

import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.core.SceneModelResolver;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.idempotency.IdempotencyStore;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;
import com.threadfit.glucopilot.ai.business.meal.service.MealAnalysisService;
import com.threadfit.glucopilot.ai.business.meal.service.MealFoodCatalogService;
import com.threadfit.glucopilot.ai.business.meal.service.impl.MealAnalysisServiceImpl;

@ExtendWith(MockitoExtension.class)
class MealAnalysisServiceTest {
    @Mock private MediaFileService media;
    @Mock private VisionAnalyzer vision;
    @Mock private MealFoodCatalogService catalog;
    @Mock private SceneModelResolver sceneModelResolver;
    @Mock private IdempotencyStore idempotency;
    private JsonUtils json;
    private MealAnalysisServiceImpl service;

    @BeforeEach void setup() {
        json = new JsonUtils(JsonMapper.builder().build());
        lenient().when(sceneModelResolver.modelCode(any())).thenReturn("qwen3-vl-plus");
        service = new MealAnalysisServiceImpl(media, vision, catalog, sceneModelResolver, json, idempotency);
    }
    @AfterEach void cleanup() { RequestContext.clear(); }

    @Test void refusesOtherUsersPhotoBeforeModelCall() {
        assertThrows(BizException.class, () -> service.analyze(1L, request()));
        verifyNoInteractions(vision, catalog);
    }
    @Test void refusesBloodGlucoseNodeForMealRecognition() {
        var request = request(); request.setNodeCode("before_breakfast");
        assertThrows(BizException.class, () -> service.analyze(1L, request));
        verifyNoInteractions(media, vision, catalog);
    }
    @Test void parsesFencedResultAndReturnsCatalogNutrition() {
        MediaFile photo = new MediaFile(); photo.setId(8L);
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(photo);
        when(vision.analyze(any(), eq(SceneCode.MEAL_ANALYSIS), anyString())).thenReturn("```json\n{\"schemaVersion\":1,\"foods\":[{\"name\":\"米饭\",\"categoryCode\":1,\"weightG\":100,\"confidence\":95}]}\n```");
        when(catalog.resolve(any(), anyString(), eq(8L))).thenReturn(new MealFoodCatalogService.Resolution(
                MealAnalysisDtos.Item.builder().foodName("米饭").foodId("1").nutritionSource("LIBRARY")
                        .weightG(new BigDecimal("100")).carbsG(new BigDecimal("25.9"))
                        .caloriesKcal(new BigDecimal("116.0")).build(), false));
        var result = service.analyze(1L, request());
        assertEquals(new BigDecimal("25.9"), result.getCarbsG());
        assertEquals(1, result.getMatchedCount());
        assertEquals(0, result.getNewFoodsCount());
    }
    @Test void invalidAndNonFoodOutputsCannotInsertFoods() {
        assertThrows(BizException.class, () -> service.parse("not-json"));
        assertThrows(BizException.class, () -> service.parse("{\"schemaVersion\":1}"));
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(new MediaFile());
        when(vision.analyze(any(), any(), anyString())).thenReturn("{\"schemaVersion\":1,\"foods\":[]}");
        assertThrows(BizException.class, () -> service.analyze(1L, request()));
        verifyNoInteractions(catalog);
    }
    @Test void cachedRetryStillRequiresPhotoOwnershipAndAvoidsModelCharge() {
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(new MediaFile());
        RequestContext.setIdempotencyKey("request-key");
        var previous = MealAnalysisDtos.Result.builder().model("qwen3-vl-plus").matchedCount(1).build();
        when(idempotency.claim(anyString())).thenReturn(new IdempotencyStore.Claim.Replay(json.toJson(previous)));
        assertEquals(1, service.analyze(1L, request()).getMatchedCount());
        verifyNoInteractions(vision, catalog);
    }
    private static MealAnalysisDtos.Request request() {
        var request = new MealAnalysisDtos.Request(); request.setMediaId(8L); request.setNodeCode("breakfast"); return request;
    }
}
