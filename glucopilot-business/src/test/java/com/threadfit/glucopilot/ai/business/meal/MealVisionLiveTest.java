package com.threadfit.glucopilot.ai.business.meal;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiVisionClientConfig;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** 显式启用的真实提供方连通性测试，仅发送空白测试图，不读用户照片。 */
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
        AiPromptTemplateService prompts = new AiPromptTemplateService(props);
        ReflectionTestUtils.invokeMethod(prompts, "init");
        var client = new AiVisionClientConfig().visionChatClient(props, prompts);
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
}
