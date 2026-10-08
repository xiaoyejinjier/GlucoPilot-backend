package com.threadfit.glucopilot.ai.prompt;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词模板管理（Spring AI 2.0 的 {@link PromptTemplate} + {@code StTemplateRenderer}）。
 * <p>
 * 替代了早期自研的 {@code PromotesLoader} / {@code SystemPromptProvider}：
 * <ul>
 *   <li>加载与渲染交给框架的 {@link PromptTemplate}，不再自己读文件 + 字符串替换；</li>
 *   <li>按<b>场景</b>组织（{@link SceneCode}），而不是一个全局单文件；</li>
 *   <li>保留「空/缺失即启动失败」的 fail-fast 语义——提示词悄悄为空比启动失败危险得多。</li>
 * </ul>
 * <p>
 * <b>模板语法要点（写提示词的人必读）</b>：模板使用 ST 语法 {@code {变量名}}。
 * 因此提示词正文里<b>不要出现成对花括号</b>（例如裸 JSON 示例 {@code {"a":1}}），
 * 否则会被当成变量表达式。需要描述 JSON 契约时请用字段清单。本类在启动时会按
 * {@code ai.scenes.<scene>.variables} 声明的变量试渲染一次，把这类错误挡在启动阶段。
 * <p>
 * 后续切 {@code ai_prompt_template} 表驱动（接口 4.11~4.13）时，只需把
 * {@link #load(SceneCode)} 的文本来源从 classpath 换成库表启用版本，本类的对外方法不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPromptTemplateService {

    private final AIProperties aiProperties;

    /** 变量语法用框架默认的 {@code {var}}；校验模式显式设为 THROW，缺变量时立刻报错而不是静默产出半截提示词。 */
    private final TemplateRenderer renderer = StTemplateRenderer.builder()
            .validationMode(ValidationMode.THROW)
            .build();

    private final Map<SceneCode, String> promptTexts = new EnumMap<>(SceneCode.class);

    private final Map<SceneCode, PromptTemplate> templates = new EnumMap<>(SceneCode.class);

    @jakarta.annotation.PostConstruct
    void init() {
        for (SceneCode scene : SceneCode.values()) {
            load(scene);
        }
    }

    private void load(SceneCode scene) {
        AIProperties.SceneProperties config = aiProperties.scene(scene);
        String path = config.getPrompt();
        if (!StringUtils.hasText(path)) {
            throw new IllegalStateException(
                    "AI 场景未配置提示词路径：ai.scenes." + scene.getConfigKey() + ".prompt");
        }
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词文件不存在：" + path);
        }
        String text;
        try {
            text = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词文件失败：" + path, e);
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("提示词内容为空，请检查文件：" + path);
        }

        PromptTemplate template = PromptTemplate.builder()
                .template(text)
                .renderer(renderer)
                .build();
        validateRenderable(scene, config, template, path);

        promptTexts.put(scene, text);
        templates.put(scene, template);
        log.info("AI 提示词加载完成 scene={} version={} path={}（{} 字）",
                scene.getCode(), config.getVersion(), path, text.length());
    }

    /**
     * 按声明的变量名试渲染一次：
     * 模板里出现未声明的 {@code {xxx}}（典型是粘贴进来的 JSON 示例）会在这里直接失败，
     * 而不是等到线上某个请求才炸。
     */
    private void validateRenderable(SceneCode scene,
                                    AIProperties.SceneProperties config,
                                    PromptTemplate template,
                                    String path) {
        Map<String, Object> probe = new LinkedHashMap<>();
        for (String variable : config.getVariables()) {
            probe.put(variable, "probe");
        }
        try {
            template.render(probe);
        } catch (RuntimeException e) {
            throw new IllegalStateException("提示词模板校验失败（scene=" + scene.getCode()
                    + "，检查是否有未声明的花括号变量，如 JSON 示例）：" + path, e);
        }
    }

    /** 场景的系统提示词原文（未渲染）。 */
    public String systemPrompt(SceneCode scene) {
        return promptTexts.get(scene);
    }

    /** 场景的提示词模板，需要变量时用 {@link PromptTemplate#render(Map)}。 */
    public PromptTemplate template(SceneCode scene) {
        return templates.get(scene);
    }

    /** 按变量渲染系统提示词。 */
    public String render(SceneCode scene, Map<String, Object> variables) {
        return templates.get(scene).render(variables);
    }

    /** 注入 ChatClient 的渲染器（与模板同源，避免两处各配一套语法）。 */
    public TemplateRenderer renderer() {
        return renderer;
    }

    /**
     * 提示词版本标签，用于 {@code chat_session.system_prompt_ver}。
     * 该列 VARCHAR(32)，故做长度保护。
     */
    public String versionTag(SceneCode scene) {
        String tag = scene.getConfigKey() + "@" + aiProperties.scene(scene).getVersion();
        return tag.length() <= 32 ? tag : tag.substring(tag.length() - 32);
    }
}
