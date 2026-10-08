package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.ai.config.AiToolConfig;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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

/**
 * <b>工具调用装配路径的端到端验证</b>——这是 {@code AiChatClientConfig} 里长期标注的「未验证项」。
 * <p>
 * 为什么必须单独测这一层：工具由 {@code ToolCallingAdvisor} 执行，而该 Advisor 只挂在
 * {@code ChatClientAutoConfiguration} 提供的 Builder 上（本项目正是从注入的 Builder clone 的）。
 * 用 {@code ChatClient.builder(model)} 手搓的 client <b>会静默丢掉全部工具</b>：
 * 表现为模型永不产生 tool_calls，从返回值与日志上都看不出来。所以这里跑真实的自动装配。
 * <p>
 * <b>另一个实测踩到的坑（假模型必须实现 {@code getDefaultOptions()}）</b>：
 * 工具回调是经由 {@code ToolCallingChatOptions} 进入请求的，而这些 options 的基类来自
 * {@code ChatModel.getDefaultOptions()}。假模型若不覆写它，prompt 的 options 会退化成
 * {@code DefaultChatOptions}，于是 {@code ToolCallingManager} 拿不到任何工具回调——
 * <b>模型只被调用一次、答复为空、不报任何错</b>。真实 provider 没这个问题
 * （{@code DeepSeekChatOptions implements ToolCallingChatOptions}，已核实）。
 */
class AiToolCallingWiringTest {

    private static final Long USER_ID = 7L;

    /** 第一次调用返回 tool_calls 回合，第二次返回最终答复；记录每次送进来的 Prompt。 */
    private static final class ToolCallingStub implements ChatModel {

        final List<Prompt> prompts = new ArrayList<>();

        /**
         * 关键：必须返回 tool-calling 类型的 options，否则工具回调进不了请求，
         * 工具会静默不执行（见类注释）。真实 provider 的 options 天然满足这一点。
         * <p>
         * 两个方法都覆写：{@code ChatModel} 上 {@code getOptions()} 与 {@code getDefaultOptions()}
         * 都是 default 方法，实测只覆写后者不足以让工具生效。
         */
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

    private ApplicationContextRunner runner(GlucoseQueryTools tools, ToolCallingStub model) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ChatClientAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                .withBean(ChatModel.class, () -> model)
                .withBean(GlucoseQueryTools.class, () -> tools)
                .withUserConfiguration(AiToolConfig.class);
    }

    private static GlucoseQueryTools tools(GlucoseRecordService recordService,
                                           GlucosePeriodService periodService) {
        return new GlucoseQueryTools(recordService, periodService, new JsonUtils(new ObjectMapper()));
    }

    /** 把第 n 次请求的全部消息串起来（含工具结果），用于断言"结果被回喂给模型"。 */
    private static String instructionsOf(ToolCallingStub model, int index) {
        return model.prompts.get(index).getInstructions().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
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

        runner(tools(recordService, periodService), model).run(context -> {
            assertThat(context).hasNotFailed();

            ToolCallback[] callbacks = context.getBean(ToolCallbackProvider.class).getToolCallbacks();
            assertThat(callbacks).hasSize(1);
            assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("query_glucose_range");

            ChatClient client = context.getBean(ChatClient.Builder.class)
                    .defaultTools((Object[]) callbacks)
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
            //    注意不能用 Prompt.getContents()——它只拼接消息的"文本"，而工具结果是
            //    ToolResponseMessage，其内容不在 getContents() 里（实测踩过，会误判成"没回喂"）。
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
     * 这条断言的价值在于钉住「{@code ChatCompletionService} 里那行 {@code .toolContext(...)} 是承重的」——
     * 少了它，凡是触发工具的问题都会把整轮请求打成异常。
     * 相比"静默返回空答复"，明确抛错显然更好，所以这里保留框架行为、不做防御性兜底，而是用测试守住它。
     */
    @Test
    void 缺toolContext时框架应明确报错而不是静默失败() {
        GlucoseRecordService recordService = mock(GlucoseRecordService.class);
        GlucosePeriodService periodService = mock(GlucosePeriodService.class);
        ToolCallingStub model = new ToolCallingStub();

        runner(tools(recordService, periodService), model).run(context -> {
            assertThat(context).hasNotFailed();
            ChatClient client = context.getBean(ChatClient.Builder.class)
                    .defaultTools((Object[]) context.getBean(ToolCallbackProvider.class).getToolCallbacks())
                    .build();

            // 刻意不传 toolContext
            assertThatThrownBy(() -> client.prompt().user("我这周血糖怎么样").call().content())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ToolContext");
        });
    }
}
