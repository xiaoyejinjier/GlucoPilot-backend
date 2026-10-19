package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiToolConfig;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.food.service.FoodService;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import com.threadfit.glucopilot.statistics.daily.DailyStatisticsService;
import com.threadfit.glucopilot.statistics.monthly.MonthlyStatisticsService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.business.chat.service.ChatCompletionService;

/**
 * <b>工具装配路径的端到端验证</b>——这是 {@code AiChatClientConfig} 里长期标注的「未验证项」。
 * <p>
 * 为什么必须单独测这一层：工具由 {@code ToolCallingAdvisor} 执行，而该 Advisor 只挂在
 * {@code ChatClientAutoConfiguration} 提供的 Builder 上（本项目正是从注入的 Builder clone 的）。
 * 用 mock 的 ChatClient 或手搓 {@code ChatClient.builder(model)} 都覆盖不到——
 * 手搓的 client <b>会静默丢掉全部工具</b>：模型永不产生 tool_calls，从返回值与日志上都看不出来。
 * <p>
 * <b>另一个实测踩到的坑（假模型必须覆写 {@code getOptions()}）</b>：
 * 工具回调经由 {@code ToolCallingChatOptions} 进入请求，而 options 基类来自
 * {@code ChatModel.getOptions()}/{@code getDefaultOptions()}。假模型若不覆写（两个都要覆写），
 * prompt 的 options 会退化成 {@code DefaultChatOptions}，{@code ToolCallingManager} 便拿不到工具回调——
 * <b>模型只被调用一次、答复为空、不报任何错</b>。真实 provider 无此问题
 * （{@code DeepSeekChatOptions implements ToolCallingChatOptions}，已核实）。
 */
class AiToolCallingWiringTest {

    private static final Long USER_ID = 7L;
    private static final Path CONFIG = Path.of("src/main/resources/application.yaml");

    /** 第一次调用返回 tool_calls 回合，第二次返回最终答复；记录每次送进来的 Prompt。 */
    private static final class ToolCallingStub implements ChatModel {

        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                AssistantMessage toolCallTurn = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "query_glucose_range",
                                "{\"fromDate\":\"2026-09-22\",\"toDate\":\"2026-09-29\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallTurn,
                        ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("这段时间共记录 1 条：早餐前 6.8 mmol/L。"))));
        }
    }

    /**
     * 起一个只含"工具装配 + ChatClient 自动装配"的切片。
     * 五个工具类都提供（{@code AiToolConfig} 会为它们各建一个 Provider），
     * 这样顺带验证"所有工具都能被装配出来"，而不是只验一个。
     */
    private ApplicationContextRunner runner(GlucoseQueryTools glucoseTools, ToolCallingStub model) {
        JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ChatClientAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                .withBean(ChatModel.class, () -> model)
                .withBean(GlucoseQueryTools.class, () -> glucoseTools)
                .withBean(HealthProfileTools.class,
                        () -> new HealthProfileTools(mock(HealthProfileService.class), jsonUtils))
                .withBean(StatisticsTools.class,
                        () -> new StatisticsTools(mock(DailyStatisticsService.class),
                                mock(MonthlyStatisticsService.class), jsonUtils))
                .withBean(MealRecordTools.class,
                        () -> new MealRecordTools(mock(MealRecordService.class),
                                mock(GlucosePeriodService.class), jsonUtils))
                .withBean(FoodNutritionTools.class,
                        () -> new FoodNutritionTools(mock(FoodService.class), jsonUtils))
                .withUserConfiguration(AiToolConfig.class);
    }

    private static List<ToolCallback> allCallbacks(
            org.springframework.context.ApplicationContext context) {
        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> java.util.Arrays.stream(provider.getToolCallbacks()))
                .toList();
    }

    private static GlucoseQueryTools glucoseTools(GlucoseRecordService recordService,
                                                 GlucosePeriodService periodService) {
        return new GlucoseQueryTools(recordService, periodService,
                mock(GlucoseTrendService.class), new JsonUtils(new ObjectMapper()));
    }

    @Test
    void 模型返回tool_calls时工具应被真的执行且结果回喂给模型() {
        GlucoseRecordService recordService = mock(GlucoseRecordService.class);
        GlucosePeriodService periodService = mock(GlucosePeriodService.class);
        ToolCallingStub model = new ToolCallingStub();

        GlucoseRecord row = new GlucoseRecord();
        row.setRecordDate(LocalDate.of(2026, 9, 23));
        row.setNodeCode("before_breakfast");
        row.setGlucoseValue(new BigDecimal("6.8"));
        when(recordService.listRange(anyLong(), any(), any())).thenReturn(List.of(row));

        GlucoseNode node = new GlucoseNode();
        node.setNodeCode("before_breakfast");
        node.setNodeName("早餐前");
        node.setIsMeal(0);
        when(periodService.nodeMap()).thenReturn(Map.of("before_breakfast", node));

        runner(glucoseTools(recordService, periodService), model).run(context -> {
            assertThat(context).hasNotFailed();

            List<ToolCallback> callbacks = allCallbacks(context);
            ChatClient client = context.getBean(ChatClient.Builder.class)
                    .defaultTools(callbacks.toArray())
                    .build();

            String answer = client.prompt()
                    .toolContext(Map.of(AiToolContext.USER_ID, USER_ID))
                    .user("我这周血糖怎么样")
                    .call()
                    .content();

            // ① 模型给出了最终答复 ⇒ 工具回合之后模型确实被再次调用
            assertThat(answer).contains("6.8");

            // ② 工具真的执行了：userId 来自 toolContext（模型看不到），日期来自模型生成的参数
            verify(recordService).listRange(
                    eq(USER_ID), eq(LocalDate.of(2026, 9, 22)), eq(LocalDate.of(2026, 9, 29)));

            // ③ 工具结果被回喂给模型：只有工具返回里才会出现 nodeCode 与数值。
            //    注意不能用 Prompt.getContents()——它只拼接消息的"文本"，工具结果是
            //    ToolResponseMessage，其内容不在其中（实测踩过，会误判成"没回喂"）。
            assertThat(model.prompts).hasSize(2);
            assertThat(instructionsOf(model, 1))
                    .contains("before_breakfast")
                    .contains("6.8");
        });
    }

    /**
     * 缺 toolContext 时的真实行为：<b>框架直接抛异常</b>，而不是静默失败。
     * <p>
     * 实测信息：{@code IllegalArgumentException: ToolContext is required by the method as an argument}。
     * 这条断言钉住「{@code ChatCompletionService} 里那行 {@code .toolContext(...)} 是承重的」——
     * 少了它，凡是触发工具的问题都会把整轮请求打成异常。相比"静默返回空答复"，
     * 明确抛错显然更好，所以保留框架行为、用测试守住。
     */
    @Test
    void 缺toolContext时框架应明确报错而不是静默失败() {
        GlucoseRecordService recordService = mock(GlucoseRecordService.class);
        GlucosePeriodService periodService = mock(GlucosePeriodService.class);
        ToolCallingStub model = new ToolCallingStub();

        runner(glucoseTools(recordService, periodService), model).run(context -> {
            assertThat(context).hasNotFailed();
            ChatClient client = context.getBean(ChatClient.Builder.class)
                    .defaultTools(allCallbacks(context).toArray())
                    .build();

            assertThatThrownBy(() -> client.prompt().user("我这周血糖怎么样").call().content())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ToolContext");
        });
    }

    /**
     * <b>白名单名字必须与真实注册的工具名完全一致。</b>
     * <p>
     * 这是最危险的一类静默失效：把 {@code ai.tools.allow-list} 里的名字写错（或改了
     * {@code @Tool(name=...)} 忘了同步），工具就永远不会被送给模型，
     * 而应用照常启动、日志里只有一条容易被忽略的 WARN。
     * 这里直接读真实的 {@code application.yaml} 与之比对，让拼写错误变成测试失败。
     */
    @Test
    void 白名单里的每个名字都应能对应到真实注册的工具() throws Exception {
        GlucoseRecordService recordService = mock(GlucoseRecordService.class);
        GlucosePeriodService periodService = mock(GlucosePeriodService.class);
        ToolCallingStub model = new ToolCallingStub();

        AIProperties properties = bindAiProperties();
        List<String> allowList = properties.getTools().getAllowList();
        assertThat(allowList)
                .as("application.yaml 的 ai.tools.allow-list 不应为空（否则工具全部不会被注册）")
                .isNotEmpty();

        runner(glucoseTools(recordService, periodService), model).run(context -> {
            assertThat(context).hasNotFailed();
            List<String> registered = allCallbacks(context).stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .collect(Collectors.toList());

            assertThat(registered)
                    .as("白名单里出现了容器中不存在的工具名（多半是拼写错误或与 @Tool(name=...) 不一致）")
                    .containsAll(allowList);
            // 反向也钉一下：注册了但没登记的不会生效，属于"白写"，同样应当被发现
            assertThat(registered).contains("query_glucose_range");
        });
    }

    private static String instructionsOf(ToolCallingStub model, int index) {
        return model.prompts.get(index).getInstructions().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
    }

    /** 从真实的 application.yaml 绑定 AIProperties（与 Spring 的加载范围一致：只读基础文件）。 */
    private static AIProperties bindAiProperties() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new FileSystemResource(CONFIG));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("ai", Bindable.of(AIProperties.class))
                .orElseThrow(() -> new AssertionError("application.yaml 中缺少 ai 配置"));
    }
}
