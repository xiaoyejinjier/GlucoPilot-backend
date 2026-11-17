package com.threadfit.glucopilot.ai.config;
import com.threadfit.glucopilot.ai.tool.runtime.AiToolRegistry;

import com.threadfit.glucopilot.ai.tool.builtin.ArticleSearchTools;
import com.threadfit.glucopilot.ai.tool.builtin.ChatHistoryTools;
import com.threadfit.glucopilot.ai.tool.builtin.FoodNutritionTools;
import com.threadfit.glucopilot.ai.tool.builtin.GlucoseQueryTools;
import com.threadfit.glucopilot.ai.tool.builtin.GlucoseStatsTools;
import com.threadfit.glucopilot.ai.tool.builtin.HealthProfileTools;
import com.threadfit.glucopilot.ai.tool.builtin.MealRecordTools;
import com.threadfit.glucopilot.ai.tool.builtin.MemoryQueryTools;
import com.threadfit.glucopilot.ai.tool.builtin.MemoryTools;
import com.threadfit.glucopilot.ai.tool.builtin.ReminderTools;
import com.threadfit.glucopilot.ai.tool.builtin.SessionStateTools;
import com.threadfit.glucopilot.ai.tool.builtin.StatisticsTools;
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
 * <b>工具数量纪律</b>：本类目前注册 13 个工具。工具越多，模型选择越容易出错，因此
 * <b>能靠"返回里多带一段信息"解决的，就不要新增工具</b>——例如血糖的聚合摘要并入
 * {@code query_glucose_stats}，而不是再拆一个"达标率"工具。
 * 新增的五个（{@code search_chat_history} / {@code get_session_state} / {@code query_glucose_stats} /
 * {@code list_reminders} / {@code search_community_articles}）一律只读，且返回体积有上限。
 * <p>
 * <b>刻意未注册的能力</b>：所有业务写操作（新增/修改/删除血糖、饮食、档案）。工具调用是模型自主决定的，
 * 把写操作交给模型等于让它能自行改用户的健康数据；医疗场景必须由用户显式确认。
 * <p>
 * <b>唯一例外：{@code save_long_term_memory}</b>（{@link MemoryTools}）。它写的是「系统对用户的记忆」：
 * 可撤销（用户可在记忆页逐条删）、有界（5 类 × 每类多条、单条 200 字）、不触碰任何业务单据状态；
 * 更重要的原因是关键词判定天生会漏（「我最近不能吃辣了」不含「记住」），语义判断只能交给模型。
 * 这条例外<b>不可推广</b>：写血糖、写饮食这类动作必须走用户显式确认，不能照抄。
 * <p>
 * <b>配对的读工具：{@code list_long_term_memory}</b>（{@link MemoryQueryTools}）。每轮真正注入 prompt 的
 * 长期记忆只有最可信的十几条，存取不进上限、条目会持续累积——没有读工具时，第 N 名以后的事实在模型眼里
 * 根本不存在，用户会觉得它记性差。读工具按类或关键词收窄，返回 total 与 truncated 让模型能诚实描述
 * 「我一共记着几条」，并且刻意不带分页游标：给了 page 就会翻页找记忆，几轮下来吃光工具调用上限。
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

    @Bean
    public ToolCallbackProvider glucoseStatsToolProvider(GlucoseStatsTools glucoseStatsTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(glucoseStatsTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider chatHistoryToolProvider(ChatHistoryTools chatHistoryTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(chatHistoryTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider sessionStateToolProvider(SessionStateTools sessionStateTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(sessionStateTools)
                .build();
    }

    /**
     * 长期记忆写入（唯一写工具）。
     * <p>
     * 单独一个 provider 而不塞进别的工具类：写入能力在审计上要能一眼看出来，
     * 「模型能写什么」与「模型能读什么」不该混在同一个类里。
     */
    @Bean
    public ToolCallbackProvider memoryToolProvider(MemoryTools memoryTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(memoryTools)
                .build();
    }

    /**
     * 长期记忆读取：{@code list_long_term_memory}。
     * <p>
     * 与写工具分开注册、也分开白名单，是为了能在不改代码的情况下单独关掉其中一半：
     * 只想让模型记、不想让它反复查（省 token）时，删掉 yaml 里这一行即可。
     */
    @Bean
    public ToolCallbackProvider memoryQueryToolProvider(MemoryQueryTools memoryQueryTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(memoryQueryTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider reminderToolProvider(ReminderTools reminderTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(reminderTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider articleSearchToolProvider(ArticleSearchTools articleSearchTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(articleSearchTools)
                .build();
    }
}
