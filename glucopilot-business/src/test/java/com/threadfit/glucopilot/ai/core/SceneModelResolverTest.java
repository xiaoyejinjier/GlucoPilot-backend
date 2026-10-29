package com.threadfit.glucopilot.ai.core;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiModelType;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.ai.scene.service.AiSceneBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 场景 → 模型 / 参数的优先级解析（后台 {@code ai_scene_config} 引入之后的核心运行时逻辑）。
 * <p>
 * 这里钉住的是"改完到底有没有生效"这条最容易含糊的链路：
 * <ol>
 *   <li>优先级必须是 <b>后台非空字段 &gt; yaml {@code ai.scenes.*} &gt; 通道默认</b>，
 *       且"后台留空"要与引入后台之前<b>完全等价</b>（否则上线就是一次静默行为变更）；</li>
 *   <li>绑定的模型编码若已停用/不存在，要回落并 WARN，而不是把凭据往错的地方用，
 *       更不能让"记录里写 A、实际调 B"重新发生；</li>
 *   <li>视觉场景的模型只能来自视觉通道（{@code model_code} 不生效必须如实反映），
 *       参数则通过请求级 OpenAI options 生效。</li>
 * </ol>
 */
class SceneModelResolverTest {

    private AIProperties properties;

    private AiRuntimeConfigService runtimeConfig;

    private AiSceneBindingService sceneBindings;

    private SceneModelResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new AIProperties();
        for (SceneCode scene : SceneCode.values()) {
            AIProperties.SceneProperties config = new AIProperties.SceneProperties();
            config.setPrompt("prompts/chat/system.md");
            properties.getScenes().put(scene.getConfigKey(), config);
        }
        properties.getMeal().setModel("qwen3-vl-plus");
        properties.getMeal().setMaxTokens(2048);
        runtimeConfig = mock(AiRuntimeConfigService.class);
        sceneBindings = mock(AiSceneBindingService.class);
        resolver = new SceneModelResolver(properties, runtimeConfig, sceneBindings);
    }

    private static AiModelSpec spec(String code, String endpoint) {
        return spec(code, endpoint, AiModelType.TEXT);
    }

    private static AiModelSpec spec(String code, String endpoint, AiModelType type) {
        return new AiModelSpec(code, code, type == AiModelType.VISION ? 2 : 1, null, null, endpoint,
                null, null, null, null, null, "db", type);
    }

    private static AiSceneBindingService.SceneBinding binding(String modelCode, Double temperature,
                                                              Integer maxTokens, Boolean thinking) {
        return new AiSceneBindingService.SceneBinding(
                SceneCode.CHAT.getCode(), modelCode, temperature, maxTokens, thinking, "db");
    }

    private static AiSceneBindingService.SceneBinding inherit() {
        return new AiSceneBindingService.SceneBinding(
                SceneCode.CHAT.getCode(), null, null, null, null, "inherit");
    }

    /**
     * Spring AI 的 DeepSeek options 里 thinking 是个 {@code Thinking} 值对象（不是 Boolean），
     * 这里只看它的类型名，避免把测试绑死在框架内部类上。
     */
    private static boolean thinkingEnabled(DeepSeekChatOptions options) {
        return options.getThinking() != null && String.valueOf(options.getThinking()).contains("ENABLED");
    }

    // ---------------------------------------------------------------- 优先级

    @Test
    void 后台留空时与引入后台之前完全等价() {
        // yaml 声明了温度与思维链，后台没有覆盖 → 必须仍然用 yaml 的值
        properties.scene(SceneCode.CHAT).setTemperature(0.7);
        properties.scene(SceneCode.CHAT).setThinking(true);
        when(sceneBindings.binding(SceneCode.CHAT)).thenReturn(inherit());
        when(runtimeConfig.specByModelCode(anyString())).thenReturn(null);
        when(runtimeConfig.textSpec(SceneCode.CHAT)).thenReturn(spec("flash", "deepseek-chat"));

        DeepSeekChatOptions options = (DeepSeekChatOptions) resolver.optionsBuilder(SceneCode.CHAT).build();

        assertEquals(0.7, options.getTemperature());
        assertTrue(thinkingEnabled(options), "yaml 的 thinking=true 必须仍然生效");
        assertTrue(resolver.hasOverrides(SceneCode.CHAT));
        assertEquals("flash", resolver.modelCode(SceneCode.CHAT));
    }

    @Test
    void 后台参数覆盖yaml() {
        properties.scene(SceneCode.CHAT).setTemperature(0.7);
        properties.scene(SceneCode.CHAT).setMaxTokens(2048);
        when(sceneBindings.binding(SceneCode.CHAT))
                .thenReturn(binding(null, 0.2, 512, Boolean.FALSE));
        when(runtimeConfig.textSpec(SceneCode.CHAT)).thenReturn(spec("flash", "deepseek-chat"));

        DeepSeekChatOptions options = (DeepSeekChatOptions) resolver.optionsBuilder(SceneCode.CHAT).build();

        assertEquals(0.2, options.getTemperature());
        assertEquals(512, options.getMaxTokens());
        assertFalse(thinkingEnabled(options), "后台显式关掉思维链必须生效");
    }

    @Test
    void 后台绑定模型优先于yaml且写入的是端点名() {
        properties.scene(SceneCode.CHAT).setModelCode("yaml-bound");
        when(sceneBindings.binding(SceneCode.CHAT)).thenReturn(binding("pro", null, null, null));
        // 绑定的编码必须能解析出端点：编码是业务起的名字，发给厂商的必须是端点名
        when(runtimeConfig.specByModelCode("pro")).thenReturn(spec("pro", "deepseek-v4-pro"));

        DeepSeekChatOptions options = (DeepSeekChatOptions) resolver.optionsBuilder(SceneCode.CHAT).build();

        assertEquals("deepseek-v4-pro", options.getModel());
        assertEquals("pro", resolver.modelCode(SceneCode.CHAT), "落库的模型编码必须是绑定的那个");
    }

    @Test
    void 绑定的模型已停用时回落且不写模型() {
        when(sceneBindings.binding(SceneCode.CHAT)).thenReturn(binding("retired", null, null, null));
        when(runtimeConfig.specByModelCode("retired")).thenReturn(null);
        when(runtimeConfig.textSpec(SceneCode.CHAT)).thenReturn(spec("flash", "deepseek-chat"));

        DeepSeekChatOptions options = (DeepSeekChatOptions) resolver.optionsBuilder(SceneCode.CHAT).build();

        // DeepSeekChatOptions 会与 spring.ai.deepseek.chat.* 合并，所以"什么都不写"并不是 null，
        // 而是通道默认；关键是**不能把解析不到的编码当成模型名写进去**
        String channelDefault = ((DeepSeekChatOptions) DeepSeekChatOptions.builder().build()).getModel();
        assertEquals(channelDefault, options.getModel(), "解析不到端点时必须交给通道默认，而不是写一个错的");
        assertEquals("flash", resolver.modelCode(SceneCode.CHAT), "落库要与实际调用一致（回落后的那个）");
    }

    @Test
    void 文本场景取向后台绑定就算有覆盖() {
        when(sceneBindings.binding(SceneCode.CHAT)).thenReturn(binding("pro", null, null, null));

        assertTrue(resolver.hasOverrides(SceneCode.CHAT));
    }

    @Test
    void 什么都没配时没有覆盖() {
        when(sceneBindings.binding(SceneCode.CHAT)).thenReturn(inherit());
        when(sceneBindings.binding(SceneCode.CHAT_IMAGE)).thenReturn(inherit());

        assertFalse(resolver.hasOverrides(SceneCode.CHAT));
        assertFalse(resolver.hasOverrides(SceneCode.CHAT_IMAGE));
    }

    // ---------------------------------------------------------------- 视觉场景

    @Test
    void 视觉场景的模型来自视觉通道而不是后台绑定() {
        when(sceneBindings.binding(SceneCode.CHAT_IMAGE)).thenReturn(inherit());
        when(runtimeConfig.visionSpec()).thenReturn(spec("qwen-vl", "db"));

        assertEquals("qwen-vl", resolver.modelCode(SceneCode.CHAT_IMAGE));
    }

    @Test
    void 视觉场景绑定了视觉模型时应采纳绑定() {
        when(sceneBindings.binding(SceneCode.CHAT_IMAGE)).thenReturn(binding("qwen-vl-max", null, null, null));
        when(runtimeConfig.specByModelCode("qwen-vl-max")).thenReturn(spec("qwen-vl-max", "qwen-vl-max-endpoint", AiModelType.VISION));
        when(runtimeConfig.visionSpec()).thenReturn(spec("qwen-vl", "db"));

        assertEquals("qwen-vl-max", resolver.modelCode(SceneCode.CHAT_IMAGE));
        // 绑定了模型就必须把它写进请求级 options（不然"绑定生效了但请求还发旧模型"）
        OpenAiChatOptions options = (OpenAiChatOptions) resolver.visionOptionsBuilder(SceneCode.CHAT_IMAGE).build();
        assertEquals("qwen-vl-max-endpoint", options.getModel());
        assertTrue(resolver.hasOverrides(SceneCode.CHAT_IMAGE));
    }

    @Test
    void 视觉场景绑定了文本模型时应回落通道视觉模型() {
        // 视觉场景绑一个文本模型是最容易犯的配置错：把 DeepSeek 塞进多模态请求只会 400，
        // 且错误信息与"绑定没生效"长得一样，所以这里必须显式回落而不是硬发
        when(sceneBindings.binding(SceneCode.CHAT_IMAGE)).thenReturn(binding("flash", null, null, null));
        when(runtimeConfig.specByModelCode("flash")).thenReturn(spec("flash", "flash-endpoint", AiModelType.TEXT));
        when(runtimeConfig.visionSpec()).thenReturn(spec("qwen-vl", "db"));

        assertEquals("qwen-vl", resolver.modelCode(SceneCode.CHAT_IMAGE));
        assertNull(resolver.visionOptionsBuilder(SceneCode.CHAT_IMAGE),
                "用途不匹配的绑定不该写进视觉请求");
    }

    @Test
    void 视觉场景没有参数时不发请求级options() {
        when(sceneBindings.binding(SceneCode.CHAT_IMAGE)).thenReturn(inherit());

        // 返回 null 表示"继承视觉模型自身的参数"，而不是发一份空 options
        assertNull(resolver.visionOptionsBuilder(SceneCode.CHAT_IMAGE));
    }

    @Test
    void 视觉场景的参数会进请求级options() {
        when(sceneBindings.binding(SceneCode.CHAT_IMAGE))
                .thenReturn(binding(null, 0.35, 256, null));

        OpenAiChatOptions options = (OpenAiChatOptions) resolver.visionOptionsBuilder(SceneCode.CHAT_IMAGE).build();

        assertEquals(0.35, options.getTemperature());
        assertEquals(256, options.getMaxCompletionTokens());
        assertTrue(resolver.hasOverrides(SceneCode.CHAT_IMAGE));
    }

    @Test
    void 饮食分析保留迁移前的模型与写死温度() {
        // 迁移前：模型与输出上限来自 ai.meal.*，温度写死 0.1；后台没有覆盖时必须原样保留
        when(sceneBindings.binding(SceneCode.MEAL_ANALYSIS)).thenReturn(inherit());

        OpenAiChatOptions options = (OpenAiChatOptions) resolver.visionOptionsBuilder(SceneCode.MEAL_ANALYSIS).build();

        assertEquals("qwen3-vl-plus", options.getModel());
        assertEquals(2048, options.getMaxCompletionTokens());
        assertEquals(0.1, options.getTemperature());
        assertTrue(resolver.hasOverrides(SceneCode.MEAL_ANALYSIS), "饮食分析恒有请求级参数");
    }

    @Test
    void 饮食分析的后台温度可以覆盖写死值() {
        when(sceneBindings.binding(SceneCode.MEAL_ANALYSIS)).thenReturn(binding(null, 0.0, null, null));

        OpenAiChatOptions options = (OpenAiChatOptions) resolver.visionOptionsBuilder(SceneCode.MEAL_ANALYSIS).build();

        assertEquals(0.0, options.getTemperature());
        assertEquals("qwen3-vl-plus", options.getModel(), "模型不受温度覆盖影响");
    }

    @Test
    void 两类optionsBuilder不允许混用() {
        // 用错类型会得到"DeepSeek 的 options 交给 OpenAI 协议 client"这种类型层面的错误，必须当场拒绝
        assertThrows(IllegalArgumentException.class, () -> resolver.optionsBuilder(SceneCode.CHAT_IMAGE));
        assertThrows(IllegalArgumentException.class, () -> resolver.visionOptionsBuilder(SceneCode.CHAT));
    }
}
