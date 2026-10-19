package com.threadfit.glucopilot.ai.prompt.service;

import com.threadfit.glucopilot.ai.core.SceneCode;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;

import java.util.List;
import java.util.Map;

/**
 * 场景提示词的只读契约（库表启用版本 &gt; classpath 内置提示词）。
 *
 * <h2>为什么它是个接口</h2>
 * 调用方很多且分散（对话链路、摘要、长期记忆抽取、视觉描述、后台管理），
 * 它们需要的只是「某个场景当前该用哪份提示词」这一个能力。抽成接口后：
 * <ul>
 *   <li>运行时的装配（快照 + TTL + 校验降级）全部藏在实现里，调用方看不到也改不坏；</li>
 *   <li>单测可以直接给一个假实现，不必为了验证"提示词怎么用"去起库表与快照刷新。</li>
 * </ul>
 *
 * <h2>实现必须守住的语义</h2>
 * <ol>
 *   <li><b>永不返回空</b>：库表没有启用版本、或那一版校验不通过时，退回 classpath 内置提示词；</li>
 *   <li><b>读库失败不能让人工智能整体不可用</b>：沿用上一份快照，只记日志；</li>
 *   <li><b>读是纯内存操作</b>：取快照是一次 {@code AtomicReference#get()}，可以放在每轮请求的链路上。</li>
 * </ol>
 */
public interface AiPromptTemplateService {

    /** 场景当前生效的系统提示词原文（未渲染）。 */
    String systemPrompt(SceneCode scene);

    /** 场景当前生效的提示词模板，需要变量时用 {@link PromptTemplate#render(Map)}。 */
    PromptTemplate template(SceneCode scene);

    /** 按变量渲染系统提示词。 */
    String render(SceneCode scene, Map<String, Object> variables);

    /** 注入 ChatClient 的渲染器（与模板同源，避免两处各配一套语法）。 */
    TemplateRenderer renderer();

    /**
     * 提示词版本标签，用于 {@code chat_session.system_prompt_ver}（形如 {@code chat@v1}）。
     * <p>
     * 库表版本与内置版本刻意用同一种写法：历史会话记录不会因为「提示词挪进后台」而在同一维度出现两种格式。
     */
    String versionTag(SceneCode scene);

    /** 当前生效来源，用于日志与后台排障：{@code db#12}（库表行 id）或 {@code classpath}。 */
    String source(SceneCode scene);

    /** 后台保存/启用后调用：本实例立即生效，不必等 TTL。 */
    void refreshNow();

    /** 当前快照里的库表版本（场景编码 → 行 id），后台排障用。 */
    Map<String, Long> dbSnapshotIds();

    /**
     * 按变量清单试渲染一次模板（<b>静态方法：不依赖实例，保存前与加载时共用同一套判据</b>）。
     * <p>
     * 提示词正文里出现未声明的 {@code {xxx}}（典型是粘贴进来的 JSON 示例）会在这里抛出
     * {@link IllegalArgumentException}，由后台接口转成 400，而不是等线上某个请求才炸。
     * <p>
     * 注意：接口的静态方法<b>不会被实现类继承</b>，实现与调用方都必须写
     * {@code AiPromptTemplateService.validateRenderable(...)}。
     *
     * @param text      提示词正文
     * @param variables 变量名清单，可为空
     * @param where     报错信息里的位置描述（场景/模板名），便于运营定位
     */
    static void validateRenderable(String text, List<String> variables, String where) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("提示词正文不能为空：" + where);
        }
        Map<String, Object> probe = new java.util.LinkedHashMap<>();
        if (variables != null) {
            for (String variable : variables) {
                if (variable != null && !variable.isBlank()) {
                    probe.put(variable.trim(), "probe");
                }
            }
        }
        try {
            PromptTemplate.builder()
                    .template(text)
                    .renderer(ST_RENDERER_FOR_VALIDATION)
                    .build()
                    .render(probe);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("提示词模板校验失败（" + where
                    + "）：正文里的花括号必须是已声明的变量，JSON 示例请改成字段清单。"
                    + "原始错误：" + e.getMessage(), e);
        }
    }

    /** 校验用渲染器（与运行时同一套语法；单独放一份是为了让校验成为不依赖实例的静态方法）。 */
    TemplateRenderer ST_RENDERER_FOR_VALIDATION = StTemplateRenderer.builder()
            .validationMode(ValidationMode.THROW)
            .build();
}
