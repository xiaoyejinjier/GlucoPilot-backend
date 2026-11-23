package com.threadfit.glucopilot.ai.business.meal.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.food.entity.FoodCategory;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.List;
import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;
import com.threadfit.glucopilot.ai.business.meal.service.MealFoodCatalogService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class MealFoodCatalogServiceImpl implements MealFoodCatalogService {
    private final FoodNutritionMapper foods;
    private final FoodCategoryMapper categories;

    /**
     * 低置信阈值（默认 70）。
     * <p>
     * <b>已知技术债</b>：这个值同时承担两个语义——①是否去营养库匹配（行为门槛，见 {@link #resolve}）；
     * ②是否要求用户确认（产品门槛，落到 Item.needsConfirmation）。调低会扩大库匹配范围，
     * 可能让低置信识别命中"看起来权威"的可疑营养数据。待规则稳定后再拆成两个配置。
     * <p>
     * 用字段注入而非构造器参数：{@code MealFoodCatalogServiceTest} 以 {@code @InjectMocks} 构造，
     * 若改为 int 构造参数会注入 0，导致阈值静默失效、全部走库匹配。
     */
    @Value("${ai.meal.low-confidence-threshold:70}")
    private int lowConfidenceThreshold = 70;

    /** 启动时打印实际生效的阈值：key 拼错会静默回落默认值，只有把非默认值跑一次才能证明绑定通了。 */
    @PostConstruct
    void logLowConfidenceThreshold() {
        log.info("餐食识别低置信阈值已生效：ai.meal.low-confidence-threshold={}", lowConfidenceThreshold);
    }

    /** 只读匹配：不建草稿、不补图片。取行规则完全交给 {@link MealFoodCatalogService#pickBest}。 */
    @Override
    public FoodNutrition findLibraryFood(String normalizedName, int category) {
        List<FoodNutrition> matches = foods.selectList(new QueryWrapper<FoodNutrition>()
                .eq("food_name", normalizedName).orderByDesc("shelf_status").orderByAsc("id"));
        return MealFoodCatalogService.pickBest(matches, category);
    }

    @Transactional
    @Override
    public Resolution resolve(MealAnalysisDtos.ModelFood input, String model) {
        return resolve(input, model, null);
    }

    /** sourceMediaId由餐食识别入口完成媒体归属校验后提供。 */
    @Transactional
    @Override
    public Resolution resolve(MealAnalysisDtos.ModelFood input, String model, Long sourceMediaId) {
        if (sourceMediaId != null && sourceMediaId <= 0) sourceMediaId = null;
        String name = MealFoodCatalogService.normalizeName(input.getName());
        // TODO(2b)：confidence=0 目前无法区分「模型未给」与「模型给了 0」，两者都会落到低置信分支，待 2b 处理。
        int confidence = input.getConfidence() != null && input.getConfidence() >= 0 && input.getConfidence() <= 100
                ? input.getConfidence() : 0;
        int category = input.getCategoryCode() == null || input.getCategoryCode() < 1 || input.getCategoryCode() > 9
                ? 9 : input.getCategoryCode();
        BigDecimal weight = positive(input.getWeightG(), 5000);
        FoodNutrition row = null;
        boolean added = false;
        if (confidence >= lowConfidenceThreshold) {
            row = findLibraryFood(name, category);
            if (row == null && bounded(input.getCarbsPer100g(), 100) != null
                    && bounded(input.getCaloriesPer100g(), 1000) != null) {
                FoodCategory selected = categories.selectById((long) category);
                FoodNutrition draft = new FoodNutrition();
                draft.setFoodName(name);
                draft.setCategoryCode(category);
                draft.setCategoryId(selected != null && Integer.valueOf(1).equals(selected.getIsEnabled()) ? selected.getId() : null);
                draft.setCarbsPer100g(bounded(input.getCarbsPer100g(), 100));
                draft.setCaloriesPer100g(bounded(input.getCaloriesPer100g(), 1000));
                draft.setSourceModel(model);
                draft.setSourceMediaId(sourceMediaId);
                draft.setCreatedBy(RequestContext.operatorIdOrZero());
                draft.setUpdatedBy(RequestContext.operatorIdOrZero());
                added = foods.insertRecognitionDraft(draft) == 1;
                row = foods.selectOne(new QueryWrapper<FoodNutrition>()
                        .eq("category_code", category).eq("food_name", name).last("LIMIT 1 FOR UPDATE"));
                if (row == null) throw new BizException(ErrorCode.INTERNAL_ERROR, "新食品登记后未能读取，请重试");
            }
        }
        if (row != null && sourceMediaId != null && row.getSourceMediaId() == null
                && "AI_ESTIMATE".equals(row.getNutritionSource()) && Integer.valueOf(0).equals(row.getShelfStatus())) {
            if (foods.attachRecognitionPhoto(row.getId(), sourceMediaId) == 1) row.setSourceMediaId(sourceMediaId);
        }
        BigDecimal carbs = row == null ? null : bounded(row.getCarbsPer100g(), 100);
        BigDecimal calories = row == null ? null : bounded(row.getCaloriesPer100g(), 1000);
        boolean available = row != null && Integer.valueOf(1).equals(row.getShelfStatus());
        String source = row == null ? "UNKNOWN"
                : "AI_ESTIMATE".equals(row.getNutritionSource()) ? "AI_ESTIMATE" : "LIBRARY";
        String libraryStatus = row == null ? "UNRESOLVED" : available ? "AVAILABLE" : "PENDING_REVIEW";
        boolean needsConfirmation = MealFoodCatalogService.needsConfirmation(confidence, libraryStatus, lowConfidenceThreshold);
        MealAnalysisDtos.Item item = MealAnalysisDtos.Item.builder()
                .foodName(row == null ? name : row.getFoodName())
                .foodId(available ? String.valueOf(row.getId()) : null)
                .libraryFoodId(row == null ? null : String.valueOf(row.getId()))
                .categoryCode(row == null ? category : row.getCategoryCode())
                .weightG(weight).carbsPer100g(carbs).caloriesPer100g(calories)
                .carbsG(portion(carbs, weight)).caloriesKcal(portion(calories, weight))
                .confidence(confidence).nutritionSource(source)
                .giValue(row == null ? null : row.getGiValue())
                .libraryStatus(libraryStatus)
                .needsConfirmation(needsConfirmation)
                .build();
        return new Resolution(item, added);
    }


    private static BigDecimal bounded(BigDecimal value, int max) {
        return value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0
                ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal value, int max) {
        BigDecimal rounded = value == null || value.signum() <= 0 ? null : bounded(value, max);
        return rounded == null || rounded.signum() <= 0 ? null : rounded;
    }

    private static BigDecimal portion(BigDecimal per100g, BigDecimal weight) {
        return per100g == null || weight == null ? null
                : per100g.multiply(weight).divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP);
    }

}
