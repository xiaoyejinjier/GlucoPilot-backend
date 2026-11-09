package com.threadfit.glucopilot.ai.business.translation.service;

import java.util.Map;

/**
 * 中英翻译能力：把一组「键 → 中文文本」翻成英文，键与顺序保持不变。
 *
 * <p><b>边界</b>：这个接口只负责「文本进、文本出」。要不要翻、翻什么、缓存多久、失败重试几次、
 * 谁能看，都属于业务侧（{@code localization.LocalizationService}）的判断；这里既不知道
 * <i>文章</i>也不知道<i>缓存表</i>，只认上面这七个键值对。
 *
 * <p><b>为什么抽象成接口</b>：{@link ModelEnglishTranslator} 依赖模型可用性与提示词版本，
 * 而缓存分代、批量策略这些逻辑在测试里希望完全绕开模型——依赖抽象就可以塞一个不联网的实现。
 *
 * @see ModelEnglishTranslator 真实实现（走 AI 模块的 utility 客户端）
 */
public interface EnglishTranslator {

    /**
     * 翻译一批文本。
     *
     * @param kind 内容类别（{@code ARTICLE_PREVIEW} / {@code ARTICLE_BODY} / {@code PROFILE_NAME}），
     *             只用于给模型一句上下文提示，不参与键的构造
     * @param source 源文本，键是业务给的稳定标识（如 {@code title} / {@code body} / {@code nickname}）
     * @return 与 {@code source} 同键集的译文；实现可以抛运行时异常表示「这批没翻成」
     */
    Map<String, String> translate(String kind, Map<String, String> source);

    /**
     * 当前生效的提示词版本标签（如 {@code translation@v1}）。
     *
     * <p>两个用途，都不能省：①进缓存哈希——换提示词等于换产出，旧缓存必须失效；
     * ②落库留痕——事后要看某条译文是哪版提示词翻的。
     */
    String promptVersion();
}
