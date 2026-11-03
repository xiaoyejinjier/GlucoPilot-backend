package com.threadfit.glucopilot.ai.memory.dto;

import java.util.List;

/**
 * 长期记忆抽取的<b>结构化输出</b>契约。
 *
 * <p>为什么要有它：抽取结果原本靠模型自由输出 JSON + 手写宽松解析（{@code parse()}）。那条路
 * 能跑，但三个问题一直存在——①模型偶尔在 JSON 外面加解释文字，只能靠文末兜底"解析失败就
 * 一条都不写"；②schema 不在契约里，改字段没人拦；③失败后无法区分"模型没给出记忆"与"格式坏了"。
 *
 * <p>改用 Spring AI 的 {@code call().entity(...)} + {@code StructuredOutputValidationAdvisor}
 * 之后：schema 由 {@code BeanOutputConverter} 生成并追加到请求里，返回内容用 JSON Schema 校验
 * （{@code maxRepeatAttempts} 内自动重试），最后反序列化成这里这个 record。
 *
 * <p>字段名必须与<b>提示词里的字段说明</b>一致（{@code key/text/confidence}），否则模型会按
 * 提示词输出、按 schema 校验失败。改字段时两处一起改。
 */
public final class MemoryExtractionDtos {

    private MemoryExtractionDtos() {
    }

    /**
     * 抽取结果根对象，对应 <code>{"memories":[{"key":…,"text":…,"confidence":…}]}</code>。
     *
     * <p>用 {@code List} 而不是单个对象：一次抽取可能得到多条记忆（不同 key）。空列表是合法
     * 结果（这轮没有值得长期记住的内容），与解析失败要区分开。
     */
    public record MemoryPayload(List<MemoryItemPayload> memories) {
    }

    /**
     * 一条候选记忆。
     *
     * @param key        记忆的维度key（如 {@code 饮食偏好}），同一 key 会覆盖旧值
     * @param text       自然语言内容，必须能独立读懂（不要出现"这个/那个"这类指代）
     * @param confidence 0~100 的把握程度；模型给出 null 时按 0 处理
     */
    public record MemoryItemPayload(String key, String text, Integer confidence) {
    }
}
