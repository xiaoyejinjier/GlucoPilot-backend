package com.threadfit.glucopilot.meal.suitability.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.AdviceResolver;
import com.threadfit.glucopilot.meal.suitability.entity.MealSuitabilityConfig;
import com.threadfit.glucopilot.meal.suitability.mapper.MealSuitabilityConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 适食判定配置装配：读取 meal_suitability_config，供 preview 与 save 两条路径共用
 * （同一配置源，保证同一餐两条路径文案一致）。
 *
 * <p><b>2026-11-27 起不再读 meal_portion_adjust_rule</b>：份量退出判定后，
 * 份量修正规则与「整餐碳水占比门槛 / UNKNOWN 跳过」两个配置键都失去作用——
 * 整餐改取最差一档，不再有占比加权。表与配置行保留（能在数据库里查到历史口径），
 * 但代码不再引用，避免出现「改了表却不生效」的错觉。
 */
@Component
@RequiredArgsConstructor
public class SuitabilityConfigService {

    public static final String KEY_ADVICE_PREFIX = "advice_";
    public static final String KEY_FOCUS_TEXT_INTRINSIC = "focus_text_intrinsic";

    private final MealSuitabilityConfigMapper configMapper;

    /** 配置键值对（文案池用 | 分隔候选）。 */
    public Map<String, String> configMap() {
        Map<String, String> map = new HashMap<>();
        for (MealSuitabilityConfig row : configMapper.selectList(Wrappers.emptyWrapper())) {
            if (row.getConfigKey() != null && row.getConfigValue() != null) {
                map.put(row.getConfigKey(), row.getConfigValue());
            }
        }
        return map;
    }

    /**
     * 重点关注文案：{foodName} 占位符替换；配置缺失时返回 null 由前端兜底。
     *
     * <p>不带 reason 参数是因为原因只剩一种（{@code INTRINSIC} 本身负担高）：
     * 份量偏大这条线已经下线，多留一个分支只会让人以为还有第二种原因。
     */
    public String focusText(Map<String, String> config, String foodName) {
        String template = config.get(KEY_FOCUS_TEXT_INTRINSIC);
        return AdviceResolver.replacePlaceholders(template, foodName, null, null);
    }
}
