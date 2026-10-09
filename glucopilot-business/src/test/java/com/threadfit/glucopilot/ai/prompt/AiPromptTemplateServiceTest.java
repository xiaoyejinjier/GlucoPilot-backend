package com.threadfit.glucopilot.ai.prompt;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词模板加载与校验。
 * <p>
 * 这里守住两条底线：
 * 1）提示词缺失或为空必须<b>直接失败</b>——静默拿到空系统提示词，表现为「模型像换了个人」，
 *    比启动失败难查得多（项目已踩过一次）；
 * 2）模板里出现未声明的花括号（典型是粘贴进来的 JSON 示例）必须在<b>启动阶段</b>暴露，
 *    而不是等到线上某个请求才炸。
 */
class AiPromptTemplateServiceTest {

    /**
     * 服务启动时会遍历全部场景，因此测试也必须把四个场景都配上（生产 yaml 同理）——
     * 场景枚举、yaml 配置、提示词文件三者必须同批落地，少一个就起不来。
     */
    private static AIProperties properties(String chatPrompt, List<String> chatVariables) {
        AIProperties properties = new AIProperties();
        for (SceneCode scene : SceneCode.values()) {
            AIProperties.SceneProperties config = new AIProperties.SceneProperties();
            config.setPrompt("prompts/chat/system.md");
            properties.getScenes().put(scene.getConfigKey(), config);
        }
        AIProperties.SceneProperties chat = properties.scene(SceneCode.CHAT);
        chat.setPrompt(chatPrompt);
        chat.setVariables(chatVariables);
        return properties;
    }

    @Test
    void 加载真实提示词并可取用() {
        AiPromptTemplateService service = new AiPromptTemplateService(
                properties("prompts/chat/system.md", new ArrayList<>()));
        service.init();

        assertTrue(service.systemPrompt(SceneCode.CHAT).contains("不能替代线下医院诊疗"));
        assertEquals("chat@v1", service.versionTag(SceneCode.CHAT));
    }

    @Test
    void 提示词文件缺失时启动即失败() {
        AiPromptTemplateService service = new AiPromptTemplateService(
                properties("prompts/not-exists/system.md", new ArrayList<>()));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::init);
        assertTrue(error.getMessage().contains("提示词文件不存在"));
    }

    @Test
    void 模板含未声明花括号时启动即失败() {
        AiPromptTemplateService service = new AiPromptTemplateService(
                properties("prompts/broken/system.md", new ArrayList<>()));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::init);
        assertTrue(error.getMessage().contains("提示词模板校验失败"));
    }

    @Test
    void 声明了变量则模板可按变量渲染() {
        AiPromptTemplateService service = new AiPromptTemplateService(
                properties("prompts/broken/system.md", List.of("foodText")));
        service.init();

        assertTrue(service.render(SceneCode.CHAT, java.util.Map.of("foodText", "米饭一碗")).contains("米饭一碗"));
    }
}
