package com.threadfit.glucopilot.meal.suitability.engine;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 单项建议文案解析器（纯函数）：preview 与 save 共用，保证同一输入返回同一文案。
 *
 * <p>优先级：
 * <ol>
 *   <li>{@code rule.adviceText} 非空 → 使用，支持 {foodName}/{carbG}/{glValue} 占位符替换
 *       （注意列宽 VARCHAR(128)，种子文案在迁移中控制长度）。</li>
 *   <li>规则无 advice_text 或未命中 → 回退档位通用句；候选池来自 meal_suitability_config
 *       （key = advice_low / advice_moderate / advice_high / advice_unknown，多个候选用 | 分隔），
 *       按 {@code itemIndex} 轮换取句，避免同餐多项同话。</li>
 *   <li>配置也缺失 → 返回 null，由前端按档位兜底。</li>
 * </ol>
 *
 * <p>文案池落表不写死代码；本类只做选择与占位符替换，不产生文案内容。
 * 一致性口径：同一 (rule, item, itemIndex, texts) → 同一文案；
 * 不承诺 preview 与 save 档位必然一致（两条路径的值归一化策略不同，是防伪造设计）。
 */
public final class AdviceResolver {

    public static final String PLACEHOLDER_FOOD_NAME = "{foodName}";
    public static final String PLACEHOLDER_CARB_G = "{carbG}";
    public static final String PLACEHOLDER_GL_VALUE = "{glValue}";
    private static final String POOL_SEPARATOR = "\\|";

    private AdviceResolver() {
    }

    /**
     * 解析单项建议文案。
     *
     * @param rule      命中的规则；UNKNOWN 项为 null
     * @param tier      最终档位（含份量修正）
     * @param item      归一化后的食物项（用于占位符取值）
     * @param itemIndex 该项在餐中的序号（兜底池轮换取句用）；可空，按 0 处理
     * @param texts     配置文案池（key = advice_low / advice_moderate / advice_high / advice_unknown）
     */
    public static String resolve(FoodSuitabilityRule rule, String tier, ItemInput item,
                                 Integer itemIndex, Map<String, String> texts) {
        if (rule != null && rule.getAdviceText() != null && !rule.getAdviceText().isBlank()) {
            return replacePlaceholders(rule.getAdviceText(),
                    item == null ? null : item.getFoodName(),
                    item == null ? null : item.getCarbsG(),
                    item == null ? null : item.getPer100gGl());
        }
        if (texts == null || texts.isEmpty() || tier == null) {
            return null;
        }
        String pool = texts.get("advice_" + tier.toLowerCase());
        if (pool == null || pool.isBlank()) {
            return null;
        }
        String[] candidates = pool.split(POOL_SEPARATOR);
        String picked = candidates[(itemIndex == null ? 0 : Math.floorMod(itemIndex, candidates.length))];
        return replacePlaceholders(picked.trim(),
                item == null ? null : item.getFoodName(),
                item == null ? null : item.getCarbsG(),
                item == null ? null : item.getPer100gGl());
    }

    /** 占位符替换：{foodName}/{carbG}/{glValue}；对应值缺失时保留原占位符由调用方兜底。 */
    public static String replacePlaceholders(String template, String foodName, BigDecimal carbG, BigDecimal glValue) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        String result = template;
        if (foodName != null) {
            result = result.replace(PLACEHOLDER_FOOD_NAME, foodName);
        }
        if (carbG != null) {
            result = result.replace(PLACEHOLDER_CARB_G, carbG.stripTrailingZeros().toPlainString() + "g");
        }
        if (glValue != null) {
            result = result.replace(PLACEHOLDER_GL_VALUE, glValue.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString());
        }
        return result;
    }
}
