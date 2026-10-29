package com.threadfit.glucopilot.ai.config;

import java.util.Locale;

/**
 * 模型的<b>用途</b>：文本对话，还是视觉（多模态）。
 *
 * <h2>为什么需要这个字段（而不是继续用 providerCode / endpoint 猜）</h2>
 * 后台一条模型配置原来只知道"厂商 + 端点"，于是"这条是视觉模型"只能靠
 * {@code model_endpoint} 与 {@code ai.vision.model} 是否相同来猜。猜错的代价很具体：
 * <ul>
 *   <li>新的视觉模型（端点与 yaml 默认值不同名）会被当成<b>文本候选</b>，标了默认就抢走文本通道；</li>
 *   <li>"设为默认"时清理旧默认不分类型，会把另一个通道的默认一起清掉；</li>
 *   <li>视觉通道无法在后台自由新增——它只认 yaml 里那一个模型名。</li>
 * </ul>
 *
 * <h2>类型 ≠ 通道</h2>
 * 通道（{@link AiChannel}）回答的是"连哪里、用哪个密钥、什么协议"；
 * 类型回答的是"这个模型能干什么"。同一个视觉模型既可以被视觉通道使用，
 * 也可以被某个场景按编码绑定——两者是正交的，所以这里只表达用途，不表达协议。
 */
public enum AiModelType {

    /** 纯文本对话/摘要/审核等。 */
    TEXT,

    /** 多模态（图片输入）。 */
    VISION;

    /**
     * 解析后台/DB 里的字符串。
     * <p>
     * 空值一律按 {@link #TEXT} 处理：V45 之前的历史行没有这个列，
     * 而"没有声明用途"的绝大多数就是文本模型；把它当视觉反而会让文本通道突然没有候选。
     *
     * @throws IllegalArgumentException 值不是 TEXT/VISION 时（配置错误必须显式暴露，不能静默降级）
     */
    public static AiModelType of(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AiModelType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的模型用途：" + value + "（只能是 TEXT 或 VISION）");
    }

    public boolean isVision() {
        return this == VISION;
    }
}
