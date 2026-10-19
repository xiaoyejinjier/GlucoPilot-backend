package com.threadfit.glucopilot.ai.business.meal;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiVisionClientConfig;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.runtime.ChatModelFactory;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import com.threadfit.glucopilot.ai.prompt.service.impl.AiPromptTemplateServiceImpl;
import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;

/**
 * 显式启用的真实提供方连通性测试，仅发送空白测试图，不读用户照片。
 * <p>
 * <b>2026-10-14 更新</b>：视觉 {@code ChatModel} 改为走<b>与生产同一条路径</b>
 * （{@link ChatModelFactory} 按 {@link AiModelSpec} 构造 OpenAI 兼容模型），
 * 再交给 {@code AiVisionClientConfig.visionChatClient(ChatModel, AiPromptTemplateService)}。
 * <p>
 * 原先是 {@code new AiVisionClientConfig().visionChatClient(props, prompts)}——那个签名在
 * {@code AiVisionClientConfig} 里直接 {@code OpenAiChatModel.builder()} 并吃 {@code ai.vision.*}。
 * 后台模型配置化之后，模型由运行时配置解析、工厂构造，装配点只接收 {@code ChatModel}；
 * 测试若继续按旧签名写，就会在合并时变成"编译不过 / 测的不是线上链路"（这个坑当天就踩到了）。
 */
@EnabledIfEnvironmentVariable(named = "RUN_QWEN_LIVE_TEST", matches = "true")
class MealVisionLiveTest {
    @Test void springAiCanSendPhotoAndParseMealSchema() throws Exception {
        AIProperties props = new AIProperties();
        props.getVision().setApiKey(System.getenv("AI_VISION_API_KEY"));
        props.getVision().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        props.getVision().setModel("qwen3-vl-plus");
        for (SceneCode scene : SceneCode.values()) {
            AIProperties.SceneProperties configured = new AIProperties.SceneProperties();
            configured.setPrompt(scene == SceneCode.MEAL_ANALYSIS
                    ? "prompts/meal-analysis/system.md" : "prompts/chat/system.md");
            props.getScenes().put(scene.getConfigKey(), configured);
        }
        // mapper=null：live 测试只用 classpath 内置提示词，不连库（见 AiPromptTemplateService 构造器）
        AiPromptTemplateService prompts = new AiPromptTemplateServiceImpl(props, null);
        ReflectionTestUtils.invokeMethod(prompts, "init");

        // 规格取自 ai.vision.*（等价于 yaml 兜底那一份），provider=2 → OpenAI 兼容协议
        ChatModel visionModel = factory().build(new AiModelSpec(
                "qwen3-vl-plus",
                "视觉模型（live 测试）",
                2,
                props.getVision().getBaseUrl(),
                props.getVision().getApiKey(),
                props.getVision().getModel(),
                props.getVision().getTemperature(),
                null,
                props.getVision().getMaxTokens(),
                null,
                Duration.ofSeconds(props.getVision().getTimeoutSeconds()),
                "yaml"));
        var client = new AiVisionClientConfig().visionChatClient(visionModel, prompts);
        AiChatClientRegistry registry = mock(AiChatClientRegistry.class);
        when(registry.visionClient(SceneCode.MEAL_ANALYSIS)).thenReturn(client);
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 64, 64); graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.getObject("probe.png")).thenReturn(output.toByteArray());
        MediaFile media = new MediaFile(); media.setStorageKey("probe.png");
        media.setMimeType("image/png"); media.setSizeBytes((long) output.size());
        String result = new VisionAnalyzer(registry, storage, props, prompts)
                .analyze(media, SceneCode.MEAL_ANALYSIS, "这是一张空白测试图。没有餐食，请按约定返回空 foods 数组。");
        String normalized = result.trim().replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        var parsed = new JsonUtils(JsonMapper.builder().build()).fromJson(normalized, MealAnalysisDtos.ModelResult.class);
        assertTrue(parsed.getFoods().isEmpty());
    }

    /**
     * 只为"把规格变成能发图的 ChatModel"而构造工厂。
     * <p>
     * 本测试不涉及工具调用：OpenAI 兼容分支只用到 baseUrl/apiKey/timeout，
     * 观测/重试/指标三个 {@code ObjectProvider} 给空实现即可（{@code ifAvailable} 在空 provider 下不做事），
     * {@code toolCallingManager} 也不会被 OpenAI 兼容分支使用。用 mock 是为了不引入 Spring 上下文。
     */
    @SuppressWarnings("unchecked")
    private static ChatModelFactory factory() {
        return new ChatModelFactory(
                mock(ToolCallingManager.class),
                (ObjectProvider<ObservationRegistry>) mock(ObjectProvider.class),
                (ObjectProvider<RetryTemplate>) mock(ObjectProvider.class),
                (ObjectProvider<MeterRegistry>) mock(ObjectProvider.class));
    }
}
