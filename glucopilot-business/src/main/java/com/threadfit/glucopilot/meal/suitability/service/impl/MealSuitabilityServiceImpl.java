package com.threadfit.glucopilot.meal.suitability.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.meal.service.MealNutritionResolver;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.mapper.FoodSuitabilityRuleMapper;
import com.threadfit.glucopilot.meal.suitability.service.MealSuitabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 适食分析实现：只读规则表 + 调纯引擎，无写库、无外部调用。
 * <p>规则版本：请求显式指定则校验存在并启用；未指定取表内最新启用版本（按字符串序，约定 YYYY.MM.N）。
 */
@Service
@RequiredArgsConstructor
public class MealSuitabilityServiceImpl implements MealSuitabilityService {

    /** 规则表启用标记 */
    private static final int ENABLED = 1;

    /** 档位文案：只允许这四类表述，禁止「适合/不建议食用」等绝对结论。 */
    private static final Map<String, String> TIER_LABELS = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "升糖负担较低",
            SuitabilityRuleEngine.TIER_MODERATE, "升糖负担中等",
            SuitabilityRuleEngine.TIER_HIGH, "升糖负担较高",
            SuitabilityRuleEngine.TIER_UNKNOWN, "信息不足");

    /** 档位兜底建议：规则未配置 advice_text 时使用，保持同一套合规口径。 */
    private static final Map<String, String> TIER_ADVICES = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "升糖负担较低，可保持当前份量",
            SuitabilityRuleEngine.TIER_MODERATE, "升糖负担中等，注意份量并搭配蔬菜和蛋白质",
            SuitabilityRuleEngine.TIER_HIGH, "升糖负担较高，建议减少份量或替换为低升糖食物",
            SuitabilityRuleEngine.TIER_UNKNOWN, "信息不足，建议补充重量或改用食品库食物");

    /** 免责声明占位文案：定稿需法务/营养师双签（方案 0.2 第 10 项）。 */
    private static final String DISCLAIMER =
            "以上结果为基于食物分类与营养数据的通用参考，不构成医疗建议；具体饮食请遵医嘱。";

    private final FoodSuitabilityRuleMapper ruleMapper;

    /** 明细归一化（只读 food_nutrition）：2b-1 起 preview 先归一化再判定。 */
    private final MealNutritionResolver nutritionResolver;

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
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(rules, items);
        return toResponse(evaluation, items, ruleVersion, rules);
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
     * <p>引擎签名与输出结构不变；四个展示字段只在这里回填（按位置与 {@code normalizedItems} 对齐）。
     */
    private MealSuitabilityDtos.PreviewResponse toResponse(MealSuitabilityDtos.Evaluation evaluation,
                                                           List<MealSuitabilityDtos.ItemInput> normalizedItems,
                                                           String ruleVersion,
                                                           List<FoodSuitabilityRule> rules) {
        // 建议文案是规则级内容：按 rule_code 回填，缺失时走档位兜底
        Map<String, String> adviceByRuleCode = new HashMap<>();
        for (FoodSuitabilityRule rule : rules) {
            if (rule.getRuleCode() != null && rule.getAdviceText() != null && !rule.getAdviceText().isBlank()) {
                adviceByRuleCode.put(rule.getRuleCode(), rule.getAdviceText());
            }
        }
        MealSuitabilityDtos.PreviewResponse response = new MealSuitabilityDtos.PreviewResponse();
        response.setTier(evaluation.getOverallTier());
        response.setTierLabel(labelOf(evaluation.getOverallTier()));
        response.setRuleVersion(ruleVersion);
        response.setWorstRuleCode(evaluation.getWorstRuleCode());
        List<MealSuitabilityDtos.PreviewItem> previewItems = new ArrayList<>(evaluation.getItems().size());
        for (int i = 0; i < evaluation.getItems().size(); i++) {
            MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(i);
            MealSuitabilityDtos.ItemInput normalized = i < normalizedItems.size() ? normalizedItems.get(i) : null;
            if (normalized != null) {
                result.setCategoryCode(normalized.getCategoryCode());
                result.setGiValue(normalized.getGiValue());
                result.setLibraryStatus(normalized.getLibraryStatus());
                result.setNutritionSource(normalized.getNutritionSource());
            }
            MealSuitabilityDtos.PreviewItem item = new MealSuitabilityDtos.PreviewItem();
            item.setFoodId(result.getFoodId());
            item.setFoodName(result.getFoodName());
            item.setTier(result.getTier());
            item.setTierLabel(labelOf(result.getTier()));
            item.setAdviceText(adviceOf(result.getRuleCode(), result.getTier(), adviceByRuleCode));
            item.setRuleCode(result.getRuleCode());
            item.setCategoryCode(result.getCategoryCode());
            item.setGiValue(result.getGiValue());
            item.setLibraryStatus(result.getLibraryStatus());
            item.setNutritionSource(result.getNutritionSource());
            previewItems.add(item);
        }
        response.setItems(previewItems);
        response.setDisclaimer(DISCLAIMER);
        return response;
    }

    private String labelOf(String tier) {
        return TIER_LABELS.getOrDefault(tier, TIER_LABELS.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }

    /** 规则有文案用规则文案，否则按档位兜底；UNKNOWN 使用信息不足文案。 */
    private String adviceOf(String ruleCode, String tier, Map<String, String> adviceByRuleCode) {
        if (ruleCode != null) {
            String advice = adviceByRuleCode.get(ruleCode);
            if (advice != null) {
                return advice;
            }
        }
        return TIER_ADVICES.getOrDefault(tier, TIER_ADVICES.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }
}
