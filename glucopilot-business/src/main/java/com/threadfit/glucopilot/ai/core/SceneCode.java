package com.threadfit.glucopilot.ai.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * AI 场景编码：AI 模块内部按「业务场景」而不是按「模型能力」组织。
 * <p>
 * 为什么按场景而不是按「聊天模型 / 视觉模型」二分：场景的调用契约本身就不同——
 * 对话要 SSE 流式 + 多轮记忆 + 工具调用；饮食分析是同步的结构化 JSON；
 * OCR 是异步任务 + 轮询 + 用户确认。路由到「哪个模型 / 哪套参数 / 哪份提示词」
 * 是场景的属性，不是模型的能力，因此这里以场景为唯一入口。
 * <p>
 * {@link #getConfigKey()} 对应 yaml 的 {@code ai.scenes.<configKey>}；
 * {@link #getCode()} 对应《接口设计文档》4.11 的 {@code sceneCode}（后续切
 * {@code ai_prompt_template} 表驱动时直接落库，不额外做映射）。
 */
@Getter
@RequiredArgsConstructor
public enum SceneCode {

    /** 纯文本对话（接口 2.23 / 2.24 / 2.25），当前唯一已实现的场景。 */
    CHAT("chat", false, "对话（文本）"),

    /** 对话内发图片（接口 2.24 msgType=2）：视觉模型先识别，再交给文本模型组织回答。 */
    CHAT_IMAGE("chat-image", true, "对话内图片识别"),

    /** 饮食分析 / 拍照估糖（接口 2.20）：视觉模型 + 结构化输出。 */
    MEAL_ANALYSIS("meal-analysis", true, "饮食分析 / 拍照估糖"),

    /** 血糖仪读数识别（接口 2.21 / 2.22）：任务化 + 用户确认。 */
    OCR_GLUCOSE("ocr-glucose", true, "血糖仪读数识别"),

    /**
     * 会话滚动摘要（② 期）：把超出记忆窗口的旧对话压缩成一段"事实"摘要。
     * <p>
     * 之所以单列一个场景而不是复用 CHAT：<b>它的产物不是给用户看的话</b>，而是内部上下文。
     * 单独成场景后，它可以有自己的提示词（后台可改）、自己的模型（可以挑更便宜的）、
     * 自己的温度（要稳定复述、不要发挥），也不会污染对话场景的人设与安全约束。
     * 它不需要视觉能力；也不该带工具（摘要时去调工具=让模型查数据，直接破坏"只压缩原文"的语义）。
     */
    SUMMARY("summary", false, "会话摘要压缩"),

    /**
     * 长期记忆抽取（③ 期）：从对话里挑出值得跨会话记住的用户事实（忌口/目标/长期状况/习惯/偏好）。
     * <p>
     * 与 {@link #SUMMARY} 同样单列场景：它的产物是**结构化 JSON**（不是给人看的文本），
     * 需要自己的提示词（后台可改可回滚）、自己的模型（可绑更便宜的）、自己的温度（要稳定抽取、不要发挥），
     * 而且不该带工具。写错长期记忆会在**后续每一轮**都影响回答，所以它的提示词约束比摘要更严
     * （明确禁止把诊断、剂量写进记忆）。
     */
    MEMORY("memory", false, "长期记忆抽取");

    private final String configKey;
    /** 是否需要视觉（多模态）模型：true 的场景在视觉 provider 接入前不可用。 */
    private final boolean requiresVision;
    /** 给运营看的中文名（后台页面直接展示，避免前端再维护一份映射）。 */
    private final String displayName;

    /** 与接口文档 / 库表对齐的场景编码。 */
    public String getCode() {
        return name();
    }

    public static SceneCode fromConfigKey(String configKey) {
        return Arrays.stream(values())
                .filter(scene -> scene.configKey.equals(configKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的 AI 场景配置键：" + configKey));
    }

    /**
     * 按库表 / 接口里的场景编码（{@link #getCode()}）查场景。
     * <p>
     * 与 {@link #fromConfigKey(String)} 的区别是<b>找不到时返回 null 而不是抛异常</b>：
     * 运行时读库拿到一个不认识的 {@code scene_code} 应当记日志并跳过，
     * 而不是让整个 AI 模块起不来（典型场景是回滚后库里留了新版才有的编码）。
     */
    public static SceneCode findByCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(scene -> scene.name().equals(trimmed))
                .findFirst()
                .orElse(null);
    }

    /**
     * 后台入参用的宽容查找：{@link #getCode()}（{@code CHAT}）与 {@link #getConfigKey()}
     * （{@code chat}）都接受，大小写不敏感。
     * <p>
     * 之所以要宽容：契约里 {@code sceneCode} 是 {@code CHAT}，而页面 URL / yaml 用的是
     * {@code chat}，前端很容易顺手传后者。若直接判为"未知场景"，表现出来的就是"配了不生效"。
     */
    public static SceneCode findByCodeOrConfigKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return Arrays.stream(values())
                .filter(scene -> scene.name().equalsIgnoreCase(trimmed)
                        || scene.configKey.equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }
}
