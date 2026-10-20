package com.threadfit.glucopilot.ai.prompt.service;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.prompt.entity.AiPromptTemplateEntity;
import com.threadfit.glucopilot.ai.prompt.mapper.AiPromptTemplateMapper;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.prompt.service.impl.AiPromptTemplateServiceImpl;

/**
 * 提示词模板加载与校验。
 * <p>
 * 这里守住三条底线：
 * <ol>
 *   <li>内置提示词缺失或为空必须<b>直接失败</b>——静默拿到空系统提示词，表现为「模型像换了个人」，
 *       比启动失败难查得多（项目已踩过一次）；</li>
 *   <li>模板里出现未声明的花括号（典型是粘贴进来的 JSON 示例）必须在<b>启动阶段 / 保存阶段</b>暴露，
 *       而不是等到线上某个请求才炸；</li>
 *   <li>库表这一层永远只是"锦上添花"：读不到、读坏了都必须退回内置提示词，
 *       绝不能因此启动失败或让线上跑坏提示词。</li>
 * </ol>
 */
class AiPromptTemplateServiceTest {

    /** 内置 CHAT 提示词里的一句固定文案，用来区分"用的是内置还是库表"。 */
    private static final String BUILTIN_MARK = "不能替代线下医院诊疗";

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

    private static AIProperties defaultProperties() {
        return properties("prompts/chat/system.md", new ArrayList<>());
    }

    /** 不连库的服务（单测与不连库的 live 测试用法）：只用 classpath 内置提示词。 */
    private static AiPromptTemplateServiceImpl builtinOnly(AIProperties properties) {
        return new AiPromptTemplateServiceImpl(properties, null);
    }

    private static AiPromptTemplateEntity row(long id, String sceneCode, String body, int versionNo, String variablesJson) {
        AiPromptTemplateEntity entity = new AiPromptTemplateEntity();
        entity.setId(id);
        entity.setSceneCode(sceneCode);
        entity.setTemplateName("测试模板");
        entity.setPromptBody(body);
        entity.setVariablesJson(variablesJson);
        entity.setVersionNo(versionNo);
        entity.setIsEnabled(1);
        return entity;
    }

    // ---------------------------------------------------------------- 内置提示词（原有语义）

    @Test
    void 加载真实提示词并可取用() {
        AiPromptTemplateServiceImpl service = builtinOnly(defaultProperties());
        service.init();

        assertTrue(service.systemPrompt(SceneCode.CHAT).contains(BUILTIN_MARK));
        assertEquals("chat@v1", service.versionTag(SceneCode.CHAT));
        assertEquals("classpath", service.source(SceneCode.CHAT));
    }

    @Test
    void 提示词文件缺失时启动即失败() {
        AiPromptTemplateServiceImpl service = builtinOnly(
                properties("prompts/not-exists/system.md", new ArrayList<>()));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::init);
        assertTrue(error.getMessage().contains("提示词文件不存在"));
    }

    @Test
    void 模板含未声明花括号时启动即失败() {
        AiPromptTemplateServiceImpl service = builtinOnly(
                properties("prompts/broken/system.md", new ArrayList<>()));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::init);
        assertTrue(error.getMessage().contains("提示词模板校验失败"));
    }

    @Test
    void 声明了变量则模板可按变量渲染() {
        AiPromptTemplateServiceImpl service = builtinOnly(
                properties("prompts/broken/system.md", List.of("foodText")));
        service.init();

        assertTrue(service.render(SceneCode.CHAT, Map.of("foodText", "米饭一碗")).contains("米饭一碗"));
    }

    // ---------------------------------------------------------------- 库表优先

    @Test
    void 库表启用版本优先于内置提示词且版本标签取自库表() {
        AiPromptTemplateMapper mapper = mock(AiPromptTemplateMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of(
                row(7L, "CHAT", "库表版提示词，变量={foodText}", 3, "[\"foodText\"]")));

        AiPromptTemplateServiceImpl service = new AiPromptTemplateServiceImpl(defaultProperties(), mapper);
        service.init();

        assertTrue(service.systemPrompt(SceneCode.CHAT).contains("库表版提示词"));
        assertFalse(service.systemPrompt(SceneCode.CHAT).contains(BUILTIN_MARK));
        assertEquals("db#7", service.source(SceneCode.CHAT));
        // 版本标签形态与内置保持一致：chat@v1 / chat@v3，历史会话记录不会出现两套写法
        assertEquals("chat@v3", service.versionTag(SceneCode.CHAT));
        assertEquals(7L, service.dbSnapshotIds().get("CHAT"));
        // 变量清单来自库表的 variables_json
        assertTrue(service.render(SceneCode.CHAT, Map.of("foodText", "米饭一碗")).contains("米饭一碗"));
        // 库里没配的场景继续用内置提示词（本测试的 fixture 里所有场景都指向同一份内置文件）
        assertEquals("classpath", service.source(SceneCode.CHAT_IMAGE));
        assertTrue(service.systemPrompt(SceneCode.CHAT_IMAGE).contains(BUILTIN_MARK));
    }

    @Test
    void 库表版本不可渲染时退回内置提示词而不是报错() {
        AiPromptTemplateMapper mapper = mock(AiPromptTemplateMapper.class);
        // 绕过接口直接写库、把 JSON 示例贴进了正文：花括号被当成变量
        when(mapper.selectEnabled()).thenReturn(List.of(
                row(9L, "CHAT", "请输出 {\"level\":\"ok\"}", 2, null)));

        AiPromptTemplateServiceImpl service = new AiPromptTemplateServiceImpl(defaultProperties(), mapper);
        service.init();

        assertTrue(service.systemPrompt(SceneCode.CHAT).contains(BUILTIN_MARK));
        assertEquals("classpath", service.source(SceneCode.CHAT));
    }

    @Test
    void 库表里有不认识的场景编码时忽略该行其余场景照常() {
        AiPromptTemplateMapper mapper = mock(AiPromptTemplateMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of(
                row(1L, "CHAT_FUTURE", "未来场景的提示词", 1, null),
                row(2L, "CHAT", "库表版提示词", 1, null)));

        AiPromptTemplateServiceImpl service = new AiPromptTemplateServiceImpl(defaultProperties(), mapper);
        service.init();

        assertTrue(service.systemPrompt(SceneCode.CHAT).contains("库表版提示词"));
        assertEquals(1, service.dbSnapshotIds().size());
    }

    @Test
    void 读库失败时启动不失败并全部使用内置提示词() {
        AiPromptTemplateMapper mapper = mock(AiPromptTemplateMapper.class);
        when(mapper.selectEnabled()).thenThrow(new IllegalStateException("table doesn't exist"));

        AiPromptTemplateServiceImpl service = new AiPromptTemplateServiceImpl(defaultProperties(), mapper);
        service.init();

        assertTrue(service.systemPrompt(SceneCode.CHAT).contains(BUILTIN_MARK));
        assertEquals("classpath", service.source(SceneCode.CHAT));
    }

    @Test
    void TTL内不重复读库refreshNow后立即生效() {
        AiPromptTemplateMapper mapper = mock(AiPromptTemplateMapper.class);
        when(mapper.selectEnabled())
                .thenReturn(List.of(row(1L, "CHAT", "第一版提示词", 1, null)))
                .thenReturn(List.of(row(2L, "CHAT", "第二版提示词", 2, null)));

        AiPromptTemplateServiceImpl service = new AiPromptTemplateServiceImpl(defaultProperties(), mapper);
        service.init();
        assertTrue(service.systemPrompt(SceneCode.CHAT).contains("第一版提示词"));

        // TTL 未到：多次读取不再打库
        service.systemPrompt(SceneCode.CHAT);
        service.systemPrompt(SceneCode.CHAT);
        verify(mapper, times(1)).selectEnabled();

        service.refreshNow();

        assertTrue(service.systemPrompt(SceneCode.CHAT).contains("第二版提示词"));
        assertEquals("chat@v2", service.versionTag(SceneCode.CHAT));
        verify(mapper, times(2)).selectEnabled();
    }

    @Test
    void 校验工具方法拒绝空正文与未声明变量() {
        assertThrows(IllegalArgumentException.class,
                () -> AiPromptTemplateService.validateRenderable("  ", List.of(), "scene=CHAT"));
        assertThrows(IllegalArgumentException.class,
                () -> AiPromptTemplateService.validateRenderable("输出 {\"a\":1}", List.of(), "scene=CHAT"));
        // 声明的变量可以正常渲染，不抛异常
        AiPromptTemplateService.validateRenderable("食物是 {foodText}", List.of("foodText"), "scene=CHAT");
    }
}
