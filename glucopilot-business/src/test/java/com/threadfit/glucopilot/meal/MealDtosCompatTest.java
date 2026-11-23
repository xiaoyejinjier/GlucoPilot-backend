package com.threadfit.glucopilot.meal;

import com.threadfit.glucopilot.meal.dto.MealDtos;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 2b-2 DTO 兼容性：旧客户端 JSON（缺全部新字段）必须原样可解析，预测字段保留；
 * 新客户端字段必须能正确绑定（confirmLowConfidence 为 Boolean，未携带时为 null）。
 */
class MealDtosCompatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyPayloadWithoutNewFieldsStillParses() {
        String legacy = """
                {
                  "recordDate": "2026-11-19",
                  "nodeCode": "BREAKFAST",
                  "foodsText": "米饭 150g",
                  "carbsG": 38.9,
                  "predictNow": 6.8,
                  "predictSuggest": 7.9,
                  "suggestText": "餐后散步 15 分钟",
                  "algorithmVersion": "v2.1",
                  "recommendationStatus": "IN_RANGE",
                  "items": [
                    {"foodId": 1, "foodName": "米饭", "weightG": 150, "carbsG": 38.9, "caloriesKcal": 174}
                  ]
                }
                """;

        MealDtos.SaveRequest request = mapper.readValue(legacy, MealDtos.SaveRequest.class);

        assertEquals(LocalDate.of(2026, 11, 19), request.getRecordDate());
        assertEquals("BREAKFAST", request.getNodeCode());
        // 旧客户端不会带新字段：null 是「走 legacy 分支」的判定依据
        assertNull(request.getClientVersion());
        assertNull(request.getRuleVersion());
        assertNull(request.getConfirmLowConfidence());
        // 预测字段保留，仍可透传解析（服务端 2b-3 起不再写入，但 DTO 不删）
        assertEquals(new BigDecimal("6.8"), request.getPredictNow());
        assertEquals("v2.1", request.getAlgorithmVersion());

        MealDtos.Item item = request.getItems().get(0);
        assertEquals(1L, item.getFoodId());
        assertNull(item.getLibraryFoodId());
        assertNull(item.getCategoryCode());
        assertNull(item.getGiValue());
        assertNull(item.getNutritionSource());
        assertNull(item.getConfidence());
        assertNull(item.getUserCorrected());
    }

    @Test
    void newPayloadBindsAllNewFields() {
        String current = """
                {
                  "recordDate": "2026-11-19",
                  "nodeCode": "LUNCH",
                  "clientVersion": "2.0.0",
                  "ruleVersion": "2026.11.1",
                  "confirmLowConfidence": true,
                  "items": [
                    {"foodName": "杂粮饭", "libraryFoodId": 7, "categoryCode": 1,
                     "weightG": 100, "carbsG": 25.9, "caloriesKcal": 116,
                     "giValue": 54.0, "nutritionSource": "AI_ESTIMATE",
                     "confidence": 90, "userCorrected": false}
                  ]
                }
                """;

        MealDtos.SaveRequest request = mapper.readValue(current, MealDtos.SaveRequest.class);

        assertEquals("2.0.0", request.getClientVersion());
        assertEquals("2026.11.1", request.getRuleVersion());
        assertEquals(Boolean.TRUE, request.getConfirmLowConfidence());
        MealDtos.Item item = request.getItems().get(0);
        assertEquals(7L, item.getLibraryFoodId());
        assertEquals(1, item.getCategoryCode());
        assertEquals(new BigDecimal("54.0"), item.getGiValue());
        assertEquals("AI_ESTIMATE", item.getNutritionSource());
        assertEquals(90, item.getConfidence());
        assertFalse(item.getUserCorrected());
        assertNotNull(item.getWeightG());
    }

    @Test
    void explicitNullConfirmFlagStaysNull() {
        String body = """
                {"recordDate": "2026-11-19", "nodeCode": "DINNER", "confirmLowConfidence": null}
                """;

        MealDtos.SaveRequest request = mapper.readValue(body, MealDtos.SaveRequest.class);

        // 三态语义：null(旧客户端/未表态) 与 false(明确不确认) 必须可区分
        assertNull(request.getConfirmLowConfidence());
    }
}
