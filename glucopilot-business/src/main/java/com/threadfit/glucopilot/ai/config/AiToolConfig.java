package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.tool.FoodNutritionTools;
import com.threadfit.glucopilot.ai.tool.GlucoseQueryTools;
import com.threadfit.glucopilot.ai.tool.HealthProfileTools;
import com.threadfit.glucopilot.ai.tool.MealRecordTools;
import com.threadfit.glucopilot.ai.tool.StatisticsTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具（Function Calling）的注册装配。
 * <p>
 * <b>每个工具类注册成一个 {@link ToolCallbackProvider} Bean</b>，由 {@code AiToolRegistry}
 * 收集后再按 {@code ai.tools.allow-list} 过滤。刻意不用"把带 {@code @Tool} 的对象直接丢给
 * ChatClient"的写法，原因有二：
 * <ol>
 *   <li><b>白名单是第一道闸</b>：{@code @Tool} 对象一旦被直接交给 client，就等于绕过了白名单——
 *       以后任何人往工具类里加一个方法，它都会立刻对模型可见，且 diff 里看不出来；</li>
 *   <li><b>显式登记便于审计</b>：想知道"模型到底能调用哪些工具"，看这个类 + yaml 白名单即可，
 *       不需要扫全项目的注解。</li>
 * </ol>
 * <p>
 * <b>新增工具类的步骤</b>：写带 {@code @Tool} 的组件 → 在这里加一个 {@code ToolCallbackProvider} Bean
 * → 在 {@code ai.tools.allow-list} 登记方法名（必须与 {@code @Tool(name=...)} 完全一致，否则启动时 WARN）。
 * <p>
 * <b>工具数量纪律</b>：本类目前注册 6 个工具。工具越多，模型选择越容易出错，因此
 * <b>能靠"返回里多带一段信息"解决的，就不要新增工具</b>——例如血糖的聚合摘要就没有单开工具，
 * 而是并进 {@code query_glucose_range} 的返回里。
 * <p>
 * <b>刻意未注册的能力</b>：所有写操作（新增/修改/删除血糖、饮食、档案）。工具调用是模型自主决定的，
 * 把写操作交给模型等于让它能自行改用户的健康数据；医疗场景必须由用户显式确认。
 */
@Configuration(proxyBeanMethods = false)
public class AiToolConfig {

    @Bean
    public ToolCallbackProvider glucoseQueryToolProvider(GlucoseQueryTools glucoseQueryTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(glucoseQueryTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider healthProfileToolProvider(HealthProfileTools healthProfileTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(healthProfileTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider statisticsToolProvider(StatisticsTools statisticsTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(statisticsTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider mealRecordToolProvider(MealRecordTools mealRecordTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(mealRecordTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider foodNutritionToolProvider(FoodNutritionTools foodNutritionTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(foodNutritionTools)
                .build();
    }
}
