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
    CHAT("chat", false),

    /** 对话内发图片（接口 2.24 msgType=2）：视觉模型先识别，再交给文本模型组织回答。 */
    CHAT_IMAGE("chat-image", true),

    /** 饮食分析 / 拍照估糖（接口 2.20）：视觉模型 + 结构化输出。 */
    MEAL_ANALYSIS("meal-analysis", true),

    /** 血糖仪读数识别（接口 2.21 / 2.22）：任务化 + 用户确认。 */
    OCR_GLUCOSE("ocr-glucose", true);

    private final String configKey;
    /** 是否需要视觉（多模态）模型：true 的场景在视觉 provider 接入前不可用。 */
    private final boolean requiresVision;

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
}
