package com.threadfit.glucopilot.ai.business.meal.service.impl;

import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.analysis.MealAnalysisSnapshotStore;
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
import java.util.List;
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
    @Mock private MealAnalysisSnapshotStore snapshots;
    private JsonUtils json;
    private MealAnalysisServiceImpl service;

    @BeforeEach void setup() {
        json = new JsonUtils(JsonMapper.builder().build());
        lenient().when(sceneModelResolver.modelCode(any())).thenReturn("qwen3-vl-plus");
        service = new MealAnalysisServiceImpl(media, vision, catalog, sceneModelResolver, json, idempotency, snapshots);
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
    @Test void emptyItemsAlwaysNeedConfirmation() {
        assertTrue(service.needsConfirmation(List.of()));
        assertTrue(service.needsConfirmation(null));
    }

    @Test void mealNeedsConfirmationOnlyWhenAnItemDoes() {
        var ok = MealAnalysisDtos.Item.builder().foodName("米饭").confidence(95).libraryStatus("AVAILABLE").build();
        assertFalse(service.needsConfirmation(List.of(ok)));
        var low = MealAnalysisDtos.Item.builder().foodName("杂粮饭").confidence(50)
                .libraryStatus("UNRESOLVED").build();
        assertTrue(service.needsConfirmation(List.of(ok, low)));
        assertFalse(ok.isNeedsConfirmation());
        assertTrue(low.isNeedsConfirmation());
    }

    @Test void replayRecalculatesNeedsConfirmationFromOldCachedJson() {
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(new MediaFile());
        RequestContext.setIdempotencyKey("request-key");
        // 旧缓存：缺 needsConfirmation 字段，且明细 confidence=50
        String oldJson = "{\"model\":\"qwen3-vl-plus\",\"matchedCount\":0,\"warnings\":[],\"items\":["
                + "{\"foodName\":\"米饭\",\"confidence\":50,\"libraryStatus\":\"UNRESOLVED\"}]}";
        when(idempotency.claim(anyString())).thenReturn(new IdempotencyStore.Claim.Replay(oldJson));
        var replayed = service.analyze(1L, request());
        assertTrue(replayed.isNeedsConfirmation());
        assertTrue(replayed.getItems().get(0).isNeedsConfirmation());
        verifyNoInteractions(vision, catalog);
    }

    @Test void replayToleratesExplicitNullNeedsConfirmation() {
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(new MediaFile());
        RequestContext.setIdempotencyKey("request-key");
        // 旧缓存：根与明细都显式写了 null
        String oldJson = "{\"model\":\"qwen3-vl-plus\",\"needsConfirmation\":null,\"items\":["
                + "{\"foodName\":\"米饭\",\"confidence\":95,\"libraryStatus\":\"AVAILABLE\",\"needsConfirmation\":null}],"
                + "\"matchedCount\":1,\"warnings\":[]}";
        when(idempotency.claim(anyString())).thenReturn(new IdempotencyStore.Claim.Replay(oldJson));
        var replayed = service.analyze(1L, request());
        assertFalse(replayed.isNeedsConfirmation());
        assertFalse(replayed.getItems().get(0).isNeedsConfirmation());
        verifyNoInteractions(vision, catalog);
    }

    @Test void analysisResultCarriesNeedsConfirmationFromItems() {
        MediaFile photo = new MediaFile(); photo.setId(8L);
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(photo);
        when(vision.analyze(any(), eq(SceneCode.MEAL_ANALYSIS), anyString()))
                .thenReturn("{\"schemaVersion\":1,\"foods\":[{\"name\":\"米饭\",\"categoryCode\":1,\"weightG\":100,\"confidence\":50}]}");
        when(catalog.resolve(any(), anyString(), eq(8L))).thenReturn(new MealFoodCatalogService.Resolution(
                MealAnalysisDtos.Item.builder().foodName("米饭").confidence(50)
                        .libraryStatus("UNRESOLVED").needsConfirmation(true).build(), false));
        var result = service.analyze(1L, request());
        assertTrue(result.isNeedsConfirmation());
        assertTrue(result.getItems().get(0).isNeedsConfirmation());
    }

    @Test void analysisIssuesTokenAndStoresSnapshot() {
        MediaFile photo = new MediaFile(); photo.setId(8L);
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(photo);
        when(vision.analyze(any(), eq(SceneCode.MEAL_ANALYSIS), anyString()))
                .thenReturn("{\"schemaVersion\":1,\"foods\":[{\"name\":\"米饭\",\"categoryCode\":1,\"confidence\":50}]}");
        when(catalog.resolve(any(), anyString(), eq(8L))).thenReturn(new MealFoodCatalogService.Resolution(
                MealAnalysisDtos.Item.builder().foodName("米饭").confidence(50).libraryStatus("UNRESOLVED").build(), false));
        when(snapshots.put(any(), anyString(), any())).thenReturn(true);

        var result = service.analyze(1L, request());

        // 32 字节 Base64URL 无填充 = 43 字符
        assertNotNull(result.getAnalysisToken());
        assertEquals(43, result.getAnalysisToken().length());
        var captor = org.mockito.ArgumentCaptor.forClass(MealAnalysisSnapshotStore.Snapshot.class);
        verify(snapshots).put(eq(1L), eq(result.getAnalysisToken()), captor.capture());
        var snapshot = captor.getValue();
        assertEquals(1L, snapshot.getUserId());
        assertEquals(8L, snapshot.getMediaId());
        assertEquals("breakfast", snapshot.getNodeCode());
        assertNotNull(snapshot.getIssuedAt());
        assertEquals(1, snapshot.getItems().size());
        assertEquals("UNRESOLVED", snapshot.getItems().get(0).getLibraryStatus());
        assertEquals(50, snapshot.getItems().get(0).getConfidence());
    }

    @Test void analysisStillSucceedsWhenSnapshotStoreFails() {
        MediaFile photo = new MediaFile(); photo.setId(8L);
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(photo);
        when(vision.analyze(any(), eq(SceneCode.MEAL_ANALYSIS), anyString()))
                .thenReturn("{\"schemaVersion\":1,\"foods\":[{\"name\":\"米饭\",\"categoryCode\":1,\"confidence\":95}]}");
        when(catalog.resolve(any(), anyString(), eq(8L))).thenReturn(new MealFoodCatalogService.Resolution(
                MealAnalysisDtos.Item.builder().foodName("米饭").confidence(95).libraryStatus("AVAILABLE").build(), false));
        when(snapshots.put(any(), anyString(), any())).thenReturn(false);

        var result = service.analyze(1L, request());

        assertNull(result.getAnalysisToken());
        assertEquals(1, result.getItems().size());
    }

    @Test void replayReturnsCachedTokenWithoutRebuildingSnapshot() {
        when(media.findOwnedMedia(8L, 1L, MediaBizType.MEAL)).thenReturn(new MediaFile());
        RequestContext.setIdempotencyKey("request-key");
        var previous = MealAnalysisDtos.Result.builder().model("qwen3-vl-plus").matchedCount(1)
                .analysisToken("cached-token").build();
        when(idempotency.claim(anyString())).thenReturn(new IdempotencyStore.Claim.Replay(json.toJson(previous)));

        assertEquals("cached-token", service.analyze(1L, request()).getAnalysisToken());
        verifyNoInteractions(snapshots, vision, catalog);
    }

    private static MealAnalysisDtos.Request request() {
        var request = new MealAnalysisDtos.Request(); request.setMediaId(8L); request.setNodeCode("breakfast"); return request;
    }
}
