package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DynamicChatModel} 与 Spring AI 2.0 的 options 契约。
 *
 * <h2>为什么这条值得单独一个测试类</h2>
 * 2.0 起 {@code ChatModel} 只剩一个默认参数入口 {@code getOptions()}；
 * {@code getDefaultOptions()} 是 {@code @Deprecated(forRemoval = true)} 的兼容垫片
 * （方法体就是 {@code return getOptions()}）。本类把三件事钉住，避免以后有人"顺手加回来"：
 * <ol>
 *   <li><b>只覆写 getOptions()</b>：反射断言 {@code DynamicChatModel} 没有声明
 *       {@code getDefaultOptions}——加了会重新出现"待删除"告警，且 2.x 后续真删接口方法时编译失败；</li>
 *   <li><b>必须返回真实 provider 的 options</b>：{@code DefaultChatClientUtils} 会
 *       {@code getOptions().mutate()} 后 {@code combineWith(请求级参数)}，再按
 *       {@code instanceof ToolCallingChatOptions.Builder} 注入 toolCallbacks。
 *       返回空实现 = 工具静默失效（模型照常回答、永不调工具、日志无异常）；</li>
 *   <li><b>请求级参数优先于基底</b>：这是 {@code SceneModelResolver} 的场景绑定
 *       （温度/上限/model 覆盖）能生效的前提，用框架真实的 {@code combineWith} 语义验证一次。</li>
 * </ol>
 */
class DynamicChatModelOptionsTest {

    /** 只关心 options 的假模型：call/stream 不参与本测试。 */
    private static final class StubModel implements ChatModel {

        private final ChatOptions options;

        StubModel(ChatOptions options) {
            this.options = options;
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("本测试不发起真实调用");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
        }
    }

    private static AiModelSpec yamlTextSpec() {
        return new AiModelSpec("deepseek-v4-pro", "DeepSeek 文本模型（yaml）", 1,
                "https://api.deepseek.com", "sk-yaml", "deepseek-v4-pro",
                0.7, null, 2048, null, Duration.ofSeconds(60), "yaml");
    }

    private static AiModelSpec dbSpec(String code, String endpoint) {
        return new AiModelSpec(code, code, 1, "https://api.deepseek.com", "sk-db", endpoint,
                0.2, null, 1024, Boolean.FALSE, Duration.ofSeconds(30), "db");
    }

    /** yaml 兜底通道：快照里只有 yaml 规格，因此必然复用容器里那份模型。 */
    private static DynamicChatModel yamlChannel(AiRuntimeConfigService runtime,
                                                ChatModelFactory factory,
                                                Supplier<ChatModel> fallback) {
        return new DynamicChatModel("text", runtime, factory,
                AiRuntimeConfigService.Snapshot::resolveTextDefault,
                AiRuntimeConfigService.Snapshot::yamlText,
                fallback);
    }

    private static AiRuntimeConfigService.Snapshot snapshotWith(AiModelSpec yamlText,
                                                               Map<String, AiModelSpec> byCode,
                                                               AiModelSpec textDefault) {
        AiModelSpec yamlVision = new AiModelSpec("qwen3-vl-plus", "视觉模型（yaml）", 2,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-vision", "qwen3-vl-plus",
                0.2, null, 512, null, Duration.ofSeconds(60), "yaml");
        return new AiRuntimeConfigService.Snapshot(1L, byCode, new HashMap<>(),
                yamlText, yamlVision, textDefault, "yaml 兜底", 0);
    }

    // ---------------------------------------------------------------- 唯一入口

    /**
     * 只覆写 {@code getOptions()}：类里不得出现已弃用的 {@code getDefaultOptions}。
     * <p>
     * 用反射而不是"能编译就算过"：{@code getDefaultOptions} 目前是 {@code forRemoval} 的默认方法，
     * 覆写了照样能编译（只有一条告警），所以必须显式断言"没有声明"。
     */
    @Test
    void 不得覆写已弃用的默认参数方法() {
        for (var method : DynamicChatModel.class.getDeclaredMethods()) {
            assertFalse("getDefaultOptions".equals(method.getName()),
                    "Spring AI 2.0 起 getDefaultOptions() 已 @Deprecated(forRemoval)；"
                            + "只应覆写 getOptions()，否则 2.x 后续版本删除该方法时编译直接失败");
        }
    }

    // ---------------------------------------------------------------- options 来源

    /**
     * yaml 通道：{@code getOptions()} 必须来自"容器里那份模型"，且是 provider 真实类型。
     * <p>
     * 这一条同时守住两个既有不变量：① yaml 规格时复用自动装配的模型（保留 RetryTemplate/观测）；
     * ② 返回的 options 是 {@code ToolCallingChatOptions}（DeepSeek 的 options 实现了它）。
     */
    @Test
    void yaml通道返回容器模型的provider级options() {
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model("deepseek-v4-pro").temperature(0.7).maxTokens(2048).build();
        ChatModel containerModel = new StubModel(options);
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(runtime.snapshot()).thenReturn(
                snapshotWith(yamlTextSpec(), new LinkedHashMap<>(), yamlTextSpec()));

        DynamicChatModel model = yamlChannel(runtime, factory, () -> containerModel);

        assertSame(options, model.getOptions(), "yaml 通道必须复用容器里的模型，而不是新建一个");
        assertInstanceOf(ToolCallingChatOptions.class, model.getOptions(),
                "options 必须是 ToolCallingChatOptions，否则 mutate() 退化成 DefaultChatOptions，工具静默失效");
        verifyNoInteractions(factory);
    }

    /** DB 默认模型通道：{@code getOptions()} 走工厂构造的那份模型。 */
    @Test
    void db默认模型通道返回工厂构造模型的options() {
        AiModelSpec db = dbSpec("glucopilot-chat", "deepseek-v4-pro");
        DeepSeekChatOptions dbOptions = DeepSeekChatOptions.builder()
                .model("deepseek-v4-pro").temperature(0.2).build();
        ChatModel built = new StubModel(dbOptions);
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        Map<String, AiModelSpec> byCode = new LinkedHashMap<>();
        byCode.put(db.modelCode(), db);
        when(runtime.snapshot()).thenReturn(snapshotWith(yamlTextSpec(), byCode, db));
        when(factory.build(db)).thenReturn(built);

        // 视觉通道没有 yaml 兜底可复用；文本通道这里同样传 null，强制走工厂
        DynamicChatModel model = yamlChannel(runtime, factory, null);

        assertSame(dbOptions, model.getOptions());
        verify(factory).build(db);
    }

    // ---------------------------------------------------------------- 合并语义

    /**
     * 请求级参数覆盖基底：{@code getOptions().mutate().combineWith(请求级).build()}
     * 的 model/temperature 以请求级为准。
     * <p>
     * 这不是在测我们自己的代码，而是把"场景绑定能生效"依赖的<b>框架语义</b>钉成回归项：
     * 一旦 Spring AI 改了合并方向，{@code SceneModelResolver.optionsBuilder(...)} 写的
     * model/温度/上限会静默失效，而这类失效在页面上完全看不出来（回答照常，只是参数没变）。
     */
    @Test
    void 请求级参数覆盖通道默认参数() {
        ChatOptions base = DeepSeekChatOptions.builder()
                .model("deepseek-v4-pro").temperature(0.7).maxTokens(2048).build();
        ChatOptions request = DeepSeekChatOptions.builder()
                .model("deepseek-chat-faster").temperature(0.0).build();

        ChatOptions merged = base.mutate().combineWith(request.mutate()).build();

        assertEquals("deepseek-chat-faster", merged.getModel(), "请求声明的 model 必须覆盖通道默认");
        assertEquals(0.0, merged.getTemperature(), "请求声明的温度必须覆盖通道默认");
        assertEquals(2048, merged.getMaxTokens(), "请求未声明的字段仍继承通道默认");
    }
}
