package com.threadfit.glucopilot.ai.memory;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * prompt token 估算器（② 期）。
 *
 * <h2>为什么需要估算，而不是精确分词</h2>
 * 我们需要的只是"这条历史装不装得下"这个判断，误差 20% 完全可接受；
 * 而引入分词库（tiktoken/jtokkit）意味着加依赖、加载词表、还要跟着厂商换 tokenizer ——
 * 代价远大于收益。真正的精度来自<b>校准</b>：每轮真实用量由厂商返回
 * （{@code chat_message.prompt_tokens}），拿它和我们的估算比，误差就被反馈修正了。
 *
 * <h2>基准比例</h2>
 * 中文（CJK）约 1 字 1 token；ASCII 约 0.25 token/字符（4 字符≈1 token）。
 * 这与 DeepSeek/OpenAI 系 tokenizer 的实际表现同量级，作为起点足够。
 *
 * <h2>校准</h2>
 * 维护一个全局缩放系数（EWMA，夹在 {@value #MIN_FACTOR}~{@value #MAX_FACTOR}）：
 * 每轮把"真实/估算"按 {@value #SMOOTHING} 的权重并进去。夹紧区间是为了防一次异常值
 * （比如厂商换了计费口径）把后续估算整体带偏——估算器只影响"装多少历史"，不该被单点带飞。
 */
@Component
public class TokenEstimator {

    /** EWMA 权重：新样本占比。取 0.3 是为了几轮内收敛、又不被单轮抖动主导。 */
    private static final double SMOOTHING = 0.3;

    private static final double MIN_FACTOR = 0.5;
    private static final double MAX_FACTOR = 2.5;

    /** 每条消息的固定开销（role 标记、分隔符等），量级很小但存在。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    private final AtomicLong factorMilli = new AtomicLong(1000);

    /** 估算一段文本的 token 数。 */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        double raw = cjk + other * 0.25;
        return (int) Math.ceil(raw * factor());
    }

    /** 估算一条消息（含固定开销）。 */
    public int estimateMessage(String text) {
        return estimate(text) + PER_MESSAGE_OVERHEAD;
    }

    /**
     * 用厂商返回的真实用量校准。
     *
     * @param actualTokens    真实 prompt_tokens（&lt;=0 表示无效样本，忽略）
     * @param estimatedTokens 我们对同一轮 prompt 的估算（&lt;=0 时无法比较，忽略）
     */
    public void calibrate(int actualTokens, int estimatedTokens) {
        if (actualTokens <= 0 || estimatedTokens <= 0) {
            return;
        }
        double observed = (double) actualTokens / estimatedTokens;
        double current = factor();
        double next = current * (1 - SMOOTHING) + observed * SMOOTHING;
        next = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, next));
        factorMilli.set(Math.round(next * 1000));
    }

    /** 当前缩放系数（排查/单测用）。 */
    public double factor() {
        return factorMilli.get() / 1000.0;
    }

    /** 重置校准（单测用）。 */
    void resetCalibration() {
        factorMilli.set(1000);
    }

    /**
     * 判断是否 CJK（中日韩统一表意文字 + 全角标点 + 假名 + 谚文）。
     * 全角标点按 1 token 估：它们在 tokenizer 里通常也是独立 token。
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3000 && c <= 0x303F)  // 中文标点
                || (c >= 0xFF00 && c <= 0xFFEF)  // 全角字符
                || (c >= 0x3040 && c <= 0x30FF)  // 假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 谚文
    }
}
