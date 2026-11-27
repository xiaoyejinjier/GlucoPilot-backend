package com.threadfit.glucopilot.meal.suitability.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.meal.service.MealNutritionResolver;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.AdviceResolver;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.mapper.FoodSuitabilityRuleMapper;
import com.threadfit.glucopilot.meal.suitability.service.MealSuitabilityService;
import com.threadfit.glucopilot.meal.suitability.service.SuitabilityConfigService;
import com.threadfit.glucopilot.meal.support.MealAnalysisTexts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 适食分析实现：只读规则/配置表 + 调纯引擎，无写库、无外部调用。
 * <p>规则版本：请求显式指定则校验存在并启用；未指定取表内最新启用版本（按字符串序，约定 YYYY.MM.N）。
 *
 * <p>V94 起：判定主指标为 per100gGL（resolver 归一化时计算）；份量修正与整餐聚合参数取自
 * meal_suitability_config / meal_portion_adjust_rule；单项建议与 save 路径共用
 * {@link AdviceResolver}（不再本地维护兜底建议 map，保证保存前后文案一致）。
 */
@Service
@RequiredArgsConstructor
public class MealSuitabilityServiceImpl implements MealSuitabilityService {

    /** 规则表启用标记 */
    private static final int ENABLED = 1;

    /** 免责声明占位文案：定稿需法务/营养师双签（方案 0.2 第 10 项）。 */
    private static final String DISCLAIMER =
            "以上结果为基于食物分类与营养数据的通用参考，不构成医疗建议；具体饮食请遵医嘱。";

    private final FoodSuitabilityRuleMapper ruleMapper;

    /** 明细归一化（只读 food_nutrition）：preview 先归一化再判定。 */
    private final MealNutritionResolver nutritionResolver;

    /** 配置与份量修正规则（与 save 路径共用同一来源）。 */
    private final SuitabilityConfigService configService;

    /** 引擎无状态、不依赖容器，直接持有可避免新增配置类。 */
    private final SuitabilityRuleEngine engine = new SuitabilityRuleEngine();

    @Override
    public MealSuitabilityDtos.PreviewResponse preview(Long userId, MealSuitabilityDtos.PreviewRequest request) {
        // userId 本期不参与判定：鉴权已在 Controller 强制，保留入参以固定服务契约（阶段 2 用于快照归属）
        List<FoodSuitabilityRule> enabledRules = ruleMapper.selectList(
                Wrappers.<FoodSuitabilityRule>lambdaQuery().eq(FoodSuitabilityRule::getIsEnabled, ENABLED));

        String ruleVersion = resolveRuleVersion(request.getRuleVersion(), enabledRules);
        List<FoodSuitabilityRule> rules = enabledRules.stream()
                .filter(rule -> ruleVersion.equals(rule.getRuleVersion()))
                .toList();

        // preview 路径客户端值优先：只补客户端没给的字段，不覆盖已给值（否则回归用例 2 的 giValue=75 会被库值顶掉）
        List<MealSuitabilityDtos.ItemInput> items = nutritionResolver.resolve(request.getItems(), true);

        Map<String, String> config = configService.configMap();
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(rules, items);
        return toResponse(evaluation, items, ruleVersion, rules, config);
    }

    /** 校验请求版本可用；未指定时取最新启用版本。 */
    private String resolveRuleVersion(String requestedVersion, List<FoodSuitabilityRule> enabledRules) {
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            boolean exists = enabledRules.stream()
                    .anyMatch(rule -> requestedVersion.equals(rule.getRuleVersion()));
            if (!exists) {
                throw new BizException(ErrorCode.BIZ_REJECTED, "规则版本不存在或未启用：" + requestedVersion);
            }
            return requestedVersion;
        }
        return enabledRules.stream()
                .map(FoodSuitabilityRule::getRuleVersion)
                .max(String::compareTo)
                .orElseThrow(() -> new BizException(ErrorCode.BIZ_REJECTED, "暂无启用的适食规则"));
    }

    /**
     * 组装响应：把归一化后的明细回填到判定结果上，前端看到的就是判定实际依据。
     * <p>建议文案走 {@link AdviceResolver}（与 save 共用）；重点关注文案取配置表。
     */
    private MealSuitabilityDtos.PreviewResponse toResponse(MealSuitabilityDtos.Evaluation evaluation,
                                                           List<MealSuitabilityDtos.ItemInput> normalizedItems,
                                                           String ruleVersion,
                                                           List<FoodSuitabilityRule> rules,
                                                           Map<String, String> config) {
        Map<String, FoodSuitabilityRule> ruleByCode = rules.stream()
                .filter(rule -> rule.getRuleCode() != null)
                .collect(Collectors.toMap(FoodSuitabilityRule::getRuleCode, Function.identity(), (a, b) -> a));

        MealSuitabilityDtos.PreviewResponse response = new MealSuitabilityDtos.PreviewResponse();
        response.setTier(evaluation.getOverallTier());
        response.setTierLabel(MealAnalysisTexts.labelOf(evaluation.getOverallTier()));
        response.setRuleVersion(ruleVersion);
        response.setWorstRuleCode(evaluation.getWorstRuleCode());
        response.setFocusItems(focusItemsOf(evaluation, config));

        List<MealSuitabilityDtos.PreviewItem> previewItems = new ArrayList<>(evaluation.getItems().size());
        for (int i = 0; i < evaluation.getItems().size(); i++) {
            MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(i);
            MealSuitabilityDtos.ItemInput normalized = i < normalizedItems.size() ? normalizedItems.get(i) : null;
            if (normalized != null) {
                result.setCategoryCode(normalized.getCategoryCode());
                result.setGiValue(normalized.getGiValue());
                result.setCarbsG(normalized.getCarbsG());
                result.setLibraryStatus(normalized.getLibraryStatus());
                result.setNutritionSource(normalized.getNutritionSource());
            }
            FoodSuitabilityRule hit = result.getRuleCode() == null ? null : ruleByCode.get(result.getRuleCode());

            MealSuitabilityDtos.PreviewItem item = new MealSuitabilityDtos.PreviewItem();
            item.setFoodId(result.getFoodId());
            item.setFoodName(result.getFoodName());
            item.setTier(result.getTier());
            item.setTierLabel(MealAnalysisTexts.labelOf(result.getTier()));
            item.setBaseTier(result.getBaseTier());
            item.setAdviceText(AdviceResolver.resolve(hit, result.getTier(), normalized, i, config));
            item.setRuleCode(result.getRuleCode());
            item.setPer100gGl(result.getPer100gGl());
            item.setGiSource(result.getGiSource());
            item.setGiReliability(result.getGiReliability());
            item.setMissingFields(result.getMissingFields());
            item.setFocused(result.getFocused());
            item.setFocusReason(result.getFocusReason());
            item.setCategoryCode(result.getCategoryCode());
            item.setGiValue(result.getGiValue());
            item.setCarbsG(result.getCarbsG());
            item.setLibraryStatus(result.getLibraryStatus());
            item.setNutritionSource(result.getNutritionSource());
            previewItems.add(item);
        }
        response.setItems(previewItems);
        response.setDisclaimer(DISCLAIMER);
        return response;
    }

    private List<MealSuitabilityDtos.FocusItem> focusItemsOf(MealSuitabilityDtos.Evaluation evaluation,
                                                             Map<String, String> config) {
        List<MealSuitabilityDtos.FocusItem> focusItems = new ArrayList<>();
        for (MealSuitabilityDtos.FocusItem focus : evaluation.getFocusItems()) {
            focus.setText(configService.focusText(config, focus.getFoodName()));
            focusItems.add(focus);
        }
        return focusItems;
    }
}
