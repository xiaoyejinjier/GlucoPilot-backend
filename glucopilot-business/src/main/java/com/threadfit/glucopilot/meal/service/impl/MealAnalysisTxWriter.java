package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.threadfit.glucopilot.meal.entity.MealFoodItem;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealFoodItemMapper;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.meal.service.MealNutritionResolver;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import com.threadfit.glucopilot.meal.suitability.engine.AdviceResolver;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.entity.MealPortionAdjustRule;
import com.threadfit.glucopilot.meal.suitability.mapper.FoodSuitabilityRuleMapper;
import com.threadfit.glucopilot.meal.suitability.service.SuitabilityConfigService;
import com.threadfit.glucopilot.meal.support.MealAnalysisTexts;
import com.threadfit.glucopilot.meal.vo.MealVos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分析落库的独立事务：读取已提交的明细与启用规则，生成结果并写回分析列。
 * <p>任何异常都向上抛出，由调用方在事务外决定是否标记 FAILED；
 * 独立成 bean 是为了避免「回滚标记」污染保存事务。
 *
 * <h2>V94：与 preview 共用同一判定链路</h2>
 * <ul>
 *   <li>明细行整体经 {@link MealNutritionResolver} 归一化（clientValuesFirst=false，
 *       {@code userCorrected=1} 的行由 resolver 内部按客户端值优先处理），
 *       回查营养库补 carbsPer100g 后计算 per100gGL——不再由 toInput 自拼判定入参。</li>
 *   <li>单项建议走 {@link AdviceResolver}（规则 advice_text 优先，档位候选池兜底），
 *       {@code MealAnalysisTexts} 只保留标签/摘要。</li>
 *   <li>单项 HIGH 落「重点关注」：meal_record.analysis_focus 存 JSON 快照
 *       （foodName/reason/text），reason 区分 INTRINSIC（本身偏高）/ PORTION（份量偏大）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealAnalysisTxWriter {

    private static final int ENABLED = 1;
    private static final int STATUS_READY = 1;
    /** analysis_focus 列宽 VARCHAR(512)，超出时截断并保留首个（碳水占比排序由引擎给出，此处按列表序截断）。 */
    private static final int FOCUS_MAX_LENGTH = 512;

    private final FoodSuitabilityRuleMapper ruleMapper;
    private final MealRecordMapper mealRecordMapper;
    private final MealFoodItemMapper mealFoodItemMapper;
    private final MealNutritionResolver nutritionResolver;
    private final SuitabilityConfigService configService;

    /** 引擎无状态、不依赖容器。 */
    private final SuitabilityRuleEngine engine = new SuitabilityRuleEngine();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @return 写入成功的分析快照（含规则版本与重点关注项）；
     * @throws IllegalStateException 暂无启用规则时抛出，由调用方标记 FAILED
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MealVos.Analysis write(Long userId, Long mealId) {
        List<MealFoodItem> rows = mealFoodItemMapper.selectList(
                new LambdaQueryWrapper<MealFoodItem>()
                        .eq(MealFoodItem::getMealId, mealId)
                        .orderByAsc(MealFoodItem::getSortNo)
                        .orderByAsc(MealFoodItem::getId));
        List<FoodSuitabilityRule> enabledRules = ruleMapper.selectList(
                Wrappers.<FoodSuitabilityRule>lambdaQuery().eq(FoodSuitabilityRule::getIsEnabled, ENABLED));

        String ruleVersion = enabledRules.stream()
                .map(FoodSuitabilityRule::getRuleVersion)
                .filter(version -> version != null && !version.isBlank())
                .max(String::compareTo)
                .orElseThrow(() -> new IllegalStateException("暂无启用的适食规则"));
        List<FoodSuitabilityRule> rules = enabledRules.stream()
                .filter(rule -> ruleVersion.equals(rule.getRuleVersion()))
                .toList();

        // 归一化：save 模式（库值覆盖，人工修正行除外），回查库补 carbsPer100g 并计算 per100gGL
        List<ItemInput> inputs = rows.stream().map(MealAnalysisTxWriter::toInput).toList();
        List<ItemInput> resolved = nutritionResolver.resolve(inputs, false);

        Map<String, String> config = configService.configMap();
        List<MealPortionAdjustRule> adjustRules = configService.adjustRules();
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(
                rules, resolved, adjustRules, configService.aggregationParams(config));

        Map<String, FoodSuitabilityRule> ruleByCode = rules.stream()
                .filter(rule -> rule.getRuleCode() != null)
                .collect(Collectors.toMap(FoodSuitabilityRule::getRuleCode, Function.identity(), (a, b) -> a));

        List<MealVos.AnalysisItem> analysisItems = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            MealFoodItem row = rows.get(i);
            MealSuitabilityDtos.ItemInput item = resolved.get(i);
            MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(i);
            FoodSuitabilityRule hit = result.getRuleCode() == null ? null : ruleByCode.get(result.getRuleCode());
            String advice = AdviceResolver.resolve(hit, result.getTier(), item, i, config);

            row.setAdviceTier(result.getTier());
            row.setAdviceReasonCode(result.getRuleCode());
            row.setAdviceText(advice);
            mealFoodItemMapper.updateById(row);
            analysisItems.add(MealVos.AnalysisItem.builder()
                    .foodName(row.getFoodName())
                    .tier(result.getTier())
                    .ruleCode(result.getRuleCode())
                    .advice(advice)
                    .build());
        }

        List<MealVos.AnalysisFocusItem> focusItems = buildFocusItems(evaluation, config);
        mealRecordMapper.update(null,
                new LambdaUpdateWrapper<MealRecord>()
                        .eq(MealRecord::getId, mealId)
                        .eq(MealRecord::getUserId, userId)
                        .set(MealRecord::getAnalysisStatus, STATUS_READY)
                        .set(MealRecord::getAnalysisTier, evaluation.getOverallTier())
                        .set(MealRecord::getAnalysisSummary,
                                MealAnalysisTexts.summaryOf(evaluation.getOverallTier()))
                        .set(MealRecord::getAnalysisFocus, serializeFocus(focusItems))
                        .set(MealRecord::getAnalysisRuleVersion, ruleVersion)
                        .set(MealRecord::getAnalysisGeneratedAt, LocalDateTime.now()));

        return MealVos.Analysis.builder()
                .tier(evaluation.getOverallTier())
                .tierLabel(MealAnalysisTexts.labelOf(evaluation.getOverallTier()))
                .summary(MealAnalysisTexts.summaryOf(evaluation.getOverallTier()))
                .ruleVersion(ruleVersion)
                .focusItems(focusItems)
                .items(analysisItems)
                .build();
    }

    /** 重点关注项 → 落库/回显 VO（文案 {foodName} 已替换）。 */
    private List<MealVos.AnalysisFocusItem> buildFocusItems(MealSuitabilityDtos.Evaluation evaluation,
                                                            Map<String, String> config) {
        List<MealVos.AnalysisFocusItem> focusItems = new ArrayList<>();
        for (MealSuitabilityDtos.FocusItem focus : evaluation.getFocusItems()) {
            focusItems.add(MealVos.AnalysisFocusItem.builder()
                    .foodName(focus.getFoodName())
                    .reason(focus.getReason())
                    .text(configService.focusText(config, focus.getReason(), focus.getFoodName()))
                    .build());
        }
        return focusItems;
    }

    /** 序列化 analysis_focus；为空返回 null（无 HIGH 项），序列化失败降级为 null 不阻断落库。 */
    private String serializeFocus(List<MealVos.AnalysisFocusItem> focusItems) {
        if (focusItems.isEmpty()) {
            return null;
        }
        try {
            String json = MAPPER.writeValueAsString(focusItems);
            if (json.length() > FOCUS_MAX_LENGTH) {
                log.warn("analysis_focus 超长（{} 字符），截断为首个重点关注项", json.length());
                json = MAPPER.writeValueAsString(focusItems.subList(0, 1));
            }
            return json.length() <= FOCUS_MAX_LENGTH ? json : null;
        } catch (JacksonException e) {
            log.warn("analysis_focus 序列化失败，按无重点关注处理: {}", e.getMessage());
            return null;
        }
    }

    /** 行 → 结构转换：只做字段搬运，不承担判定字段拼装（判定输入一律经 resolver 归一化）。 */
    private static MealSuitabilityDtos.ItemInput toInput(MealFoodItem row) {
        MealSuitabilityDtos.ItemInput input = new MealSuitabilityDtos.ItemInput();
        input.setFoodId(row.getFoodId());
        input.setLibraryFoodId(row.getLibraryFoodId());
        input.setFoodName(row.getFoodName());
        input.setCategoryCode(row.getCategoryCode());
        input.setWeightG(row.getWeightG());
        input.setCarbsG(row.getCarbsG());
        input.setCaloriesKcal(row.getCaloriesKcal());
        input.setGiValue(row.getGiValue());
        input.setConfidence(row.getConfidence());
        input.setNutritionSource(row.getNutritionSource());
        input.setUserCorrected(row.getUserCorrected() != null && row.getUserCorrected() == 1);
        input.setLibraryStatus(row.getLibraryStatus());
        return input;
    }
}
