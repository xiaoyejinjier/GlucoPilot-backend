package com.threadfit.glucopilot.common.mq.job;

import java.util.Map;

/**
 * AI 后台任务的类型与路由键。
 *
 * <h2>为什么类型名要大写、路由键要小写分层</h2>
 * <p>类型名进消息体，是**契约**，跟着代码走版本（改名等于改契约，得同版本发布）；
 * 路由键进交换机，是**分流规则**，运维要能看懂、要能在管理台按前缀过滤。
 * 两者用不同的拼法，看日志时能一眼分清"这是哪种活"和"它去了哪条队列"。
 *
 * <h2>为什么路由键是 {@code job.<域>.<动作>}</h2>
 * <p>队列绑定用 {@code job.moderation.#} / {@code job.memory.#} / {@code job.ocr.#}：
 * 新增一种动作只要补一个常量，队列绑定不用改；死信也按同样的前缀分桶，
 * 一个域炸了不会把别的域的死信搅在一起。
 */
public final class JobTypes {

    /** 审核文章（先审后发：只有 PASS 才会被发布）。 */
    public static final String MODERATION_ARTICLE = "MODERATION_ARTICLE";

    /** 审核评论（先发后审：REJECT 时把 is_hidden 置 1）。 */
    public static final String MODERATION_COMMENT = "MODERATION_COMMENT";

    /** 会话结束后抽取长期记忆。 */
    public static final String MEMORY_EXTRACT = "MEMORY_EXTRACT";

    /** 图片 OCR 识别（血糖仪读数等）。 */
    public static final String OCR_RECOGNIZE = "OCR_RECOGNIZE";

    private static final Map<String, String> ROUTING_KEYS = Map.of(
            MODERATION_ARTICLE, "job.moderation.article",
            MODERATION_COMMENT, "job.moderation.comment",
            MEMORY_EXTRACT, "job.memory.extract",
            OCR_RECOGNIZE, "job.ocr.recognize");

    private JobTypes() {
    }

    /**
     * 类型 → 路由键。
     *
     * @throws IllegalArgumentException 未知类型（宁可在发送前炸，也不要发到一条没人绑的键上被静默丢弃）
     */
    public static String routingKeyOf(String type) {
        String key = ROUTING_KEYS.get(type);
        if (key == null) {
            throw new IllegalArgumentException("未知的任务类型：" + type);
        }
        return key;
    }

    /** 是否是我们认识的任务类型（消费端用它把脏消息判成不可重试）。 */
    public static boolean known(String type) {
        return type != null && ROUTING_KEYS.containsKey(type);
    }
}
