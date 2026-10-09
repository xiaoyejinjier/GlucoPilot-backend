package com.threadfit.glucopilot.ai.support;

import org.springframework.stereotype.Component;

/**
 * <b>开头英文句子的清理器：把回答最前面那句英文整句去掉。</b>
 * <p>
 * <b>为什么需要它（这是实测结论，不是防御性编程）：</b>开启工具调用后，模型经常在正文最前面加一句
 * "我要去查一下……"式的说明句，而这句话<b>有时是英文</b>，实测出现过
 * {@code "I'll check your monthly glucose stats.本月（10 月）血糖情况分析如下"}、
 * {@code "I'll check your records for this month.这个月（10 月）的血糖…"}。
 * 系统提示词里已经明确写过「始终使用中文回答，不要输出英文，包括开场白……
 * （例如不要出现 "I'll check your records" 这类英文起头）」，<b>仍然拦不住</b>，
 * 所以这里改为在服务端做确定性的兜底。
 * <p>
 * <b>为什么不在流式增量上拦：</b>排查时用 chunk 级日志确认过真实的流结构——
 * 工具轮的增量正文是空的，{@code tool_calls} 的结束原因<b>根本不会传到业务代码</b>，
 * 整条流只有最后一个 chunk 带 {@code finish=STOP}。也就是说这句英文属于
 * <b>最终答案那一轮自己的开头</b>，不存在"工具轮边界"可利用；
 * 而要在增量上下发前判断"这是不是英文铺垫"，就必须压住增量直到出现中文，
 * 那会把每一轮的首字都推后（首字延迟已有 2~3 秒，不该再付这个代价）。
 * 因此这里只清理<b>组装好的正文</b>：{@code done.contentText} 与落库正文都不含这句英文，
 * 前端在 {@code done} 时会用 {@code contentText} 整体替换气泡内容
 * （{@code pages/voice/voice.js} 的 {@code finishRound}），最终呈现与后续会话记忆都是干净的；
 * 代价是流式过程中仍会短暂闪出这句英文。
 * <p>
 * <b>判定刻意保守，宁可漏杀不可错杀</b>（满足全部条件才删）：
 * <ol>
 *   <li>正文里<b>存在中文</b>——纯英文回答说明用户要的就是英文（或模型另有问题），不归这里管；</li>
 *   <li>开头到第一个中文字符之间的前缀里<b>没有任何中文</b>；</li>
 *   <li>前缀<b>以英文句号/问号/感叹号结尾</b>——只删"完整句子"，
 *       所以 {@code "HbA1c 是糖化血红蛋白"}、{@code "7.7 mmol/L 属于偏高"} 这类
 *       以英文词/单位开头的正常回答不会被误删；</li>
 *   <li>前缀至少<b>两个空格分隔的词</b>且含英文字母——单个缩写（{@code "GI."}）不算句子；</li>
 *   <li>前缀长度在 {@value #MIN_PREFIX_CHARS}~{@value #MAX_PREFIX_CHARS} 之间——
 *       太短的多半是缩写，太长的说明整段都是英文，不该由这里砍。</li>
 * </ol>
 * 前缀里句号后必须紧跟空白或位于末尾（{@code ". "}），这样 {@code "7.7"} 这种小数点不会被当句号。
 * <p>
 * 这是一个"内容兜底"而不是"合规拦截"：它不判断内容是否安全，只处理语言。
 * 医疗安全仍然由 {@code MedicalGuardAdvisor} 与提示词软约束负责。
 */
@Component
public class EnglishPreambleGuard {

    /** 前缀短于这个长度不处理：多半是缩写或单位，不是句子。 */
    static final int MIN_PREFIX_CHARS = 8;

    /** 前缀长于这个长度不处理：说明整篇都是英文，不该由这里砍。 */
    static final int MAX_PREFIX_CHARS = 200;

    /**
     * 去掉正文最前面的整句英文。
     *
     * @return 去掉后的正文；不满足条件时原样返回（含 {@code null}）
     */
    public String apply(String reply) {
        if (reply == null || reply.isEmpty()) {
            return reply;
        }
        int cjkIndex = indexOfCjk(reply);
        // 条件 1：整篇没有中文 → 不管
        if (cjkIndex < 0) {
            return reply;
        }
        String prefix = reply.substring(0, cjkIndex);
        // 条件 2/5：前缀为空或长度不在范围内 → 不管
        if (prefix.length() < MIN_PREFIX_CHARS || prefix.length() > MAX_PREFIX_CHARS) {
            return reply;
        }
        String trimmed = prefix.strip();
        // 条件 4：至少两个词且含英文字母
        if (!hasAsciiLetter(trimmed) || countWords(trimmed) < 2) {
            return reply;
        }
        if (endsWithEnglishTerminator(trimmed)) {
            return reply.substring(cjkIndex).stripLeading();
        }
        if (followedByChineseTerminator(reply, cjkIndex, trimmed)) {
            // 英文句子是用中文句号收的尾，那个标点也要一起去掉，否则正文会以孤零零的"。"开头
            return reply.substring(cjkIndex + 1).stripLeading();
        }
        return reply;
    }

    /** 条件 3（主）：前缀本身以英文句末标点收尾，且该标点不是小数点。 */
    private static boolean endsWithEnglishTerminator(String trimmed) {
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last != '.' && last != '?' && last != '!') {
            return false;
        }
        // "7.7 mmol/L." 这类：末位句号前是数字，视为小数点，不当作句子结尾
        return trimmed.length() < 2 || !Character.isDigit(trimmed.charAt(trimmed.length() - 2));
    }

    /**
     * 条件 3（次）：英文句子用了中文句号收尾（{@code "I'll check your stats。中文…"}）。
     * <p>
     * 比主判定多要一条"至少三个词"：这样 {@code "7.7 mmol/L。"} 这种以数值+单位开头的
     * 正常回答不会被当成英文句子删掉（它只有两个词），而
     * {@code "I'll check your monthly stats。"}（五个词）会被删。
     */
    private static boolean followedByChineseTerminator(String reply, int cjkIndex, String trimmed) {
        if (countWords(trimmed) < 3) {
            return false;
        }
        char first = reply.charAt(cjkIndex);
        return first == '。' || first == '！' || first == '？' || first == '；' || first == '，' || first == '、';
    }

    /** 第一个中文字符（含中文标点、全角符号）的下标；没有则返回 -1。 */
    private static int indexOfCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 是否中文/中文标点/全角字符。
     * 用 {@link Character.UnicodeScript} 判断，避免手写区间时漏掉扩展区；
     * 表意文字（含日文汉字）与全角标点都算——本项目的回答只会是中文或英文，
     * 把日文汉字也算作中文不会造成误判。
     */
    private static boolean isCjk(char c) {
        if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
            return true;
        }
        // 中文标点（，。！？：；、（）【】「」等）与全角字符
        return (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    private static boolean hasAsciiLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    private static int countWords(String text) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                words++;
            }
        }
        return words;
    }
}
