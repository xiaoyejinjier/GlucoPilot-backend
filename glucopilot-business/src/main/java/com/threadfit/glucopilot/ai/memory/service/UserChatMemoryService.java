package com.threadfit.glucopilot.ai.memory.service;

import com.threadfit.glucopilot.ai.memory.dto.UserChatMemoryDtos;

import java.util.List;
import java.util.Set;

/**
 * 跨会话长期记忆（③ 期）：抽取、注入读取与用户自助删除。
 *
 * <h2>为什么它比摘要更需要保守</h2>
 * 摘要是"这段对话聊了什么"，记忆是"你是谁"。一条写错的长期记忆（例如把"用户问过二甲双胍怎么吃"
 * 记成"用户在服用二甲双胍"）会在<b>后续每一轮</b>都影响回答，而且用户根本看不到自己被记错了。
 * 因此实现里设了四道闸：键固定（{@link #ALLOWED_KEYS}）、置信度门限、内容体检（诊断/用药词拦截）、
 * 来源可追溯且可删。
 *
 * <h2>触发时机（刻意不做"每轮都抽"）</h2>
 * <ul>
 *   <li><b>会话结束时</b>（用户点"新对话"、会话被删除）——长期记忆本来就不该每轮都写；</li>
 *   <li><b>用户显式要求记住时</b>（{@link #looksExplicit(String)}）——用户主动表达，最可信。</li>
 * </ul>
 * 每轮都抽的成本是对话的两倍，噪声也大得多。
 *
 * <h2>两处读取的失败语义刻意不同</h2>
 * <ul>
 *   <li>{@link #forInjection(Long)}：<b>读失败返回空列表</b>（记忆是增强项，不能让这一轮问答失败）；</li>
 *   <li>{@link #listOwnForUser(Long)}：<b>读失败照常抛出</b>（返回空等于告诉用户"系统没记你任何东西"）。</li>
 * </ul>
 */
public interface UserChatMemoryService {

    /**
     * 允许的记忆键（与提示词里的五类一一对应）：模型自创的键一律丢弃，避免记忆表变成大杂烩。
     * <p>
     * 放在接口上而不是实现里，是因为它是这套能力的<b>对外约定</b>：写入侧要按它拦，读取侧要按它解释。
     */
    Set<String> ALLOWED_KEYS = Set.of("diet_taboo", "glucose_goal", "health_context", "habit", "preference");

    /** 送入上下文的一条记忆。 */
    record MemoryItem(String key, String text, int confidence) {
    }

    /**
     * 取要注入上下文的记忆：按置信度降序、更新时间降序，最多 {@code ai.memory.inject-max-items} 条。
     * <p>
     * 读失败返回空列表：记忆是增强项，读不到就当没有，绝不让这一轮问答失败。
     */
    List<MemoryItem> forInjection(Long userId);

    /**
     * 用户自己看自己的记忆（C 端合规自助）：返回全部有效记忆，<b>不按注入上限截断</b>、不返回内部字段。
     * <p>
     * 读失败<b>照常抛出</b>——"系统到底记住了我什么"这个问题不能用空列表糊弄过去。
     */
    List<UserChatMemoryDtos.MemoryRow> listOwnForUser(Long userId);

    /** 用户自助删除全部长期记忆（合规：用户有权清掉系统对他的记忆）。返回删除条数。 */
    int deleteAllForUser(Long userId);

    /**
     * 账号注销：物理删除该用户全部长期记忆。
     * <p>
     * 与 {@link #deleteAllForUser} 的区别是"逻辑删除 vs 物理删除"：注销后不能留行，
     * 否则系统仍然"记得"这个已经不存在的人。
     */
    void purgeAccountData(Long userId);

    /**
     * 异步抽取某个会话的长期记忆。
     * <p>
     * <b>不阻塞、不抛异常</b>：单飞（同一会话同时只跑一个抽取任务），失败只记日志——
     * 抽取是增强项，绝不能因为一次抽取失败影响已经成功的回复或刚建好的会话。
     *
     * @param reason 触发原因（{@code explicit} / {@code session-ended}），只用于日志
     */
    void extractAsync(Long userId, Long sessionId, String reason);

    /** 当前是否有抽取任务在跑（E2E/断言用）。 */
    boolean inFlight();

    /**
     * 用户消息里是否含"记住…"这类显式要求（决定要不要立刻抽取一次）。
     * <p>
     * 静态方法：判据是纯文本规则，不依赖任何状态；接口静态方法不会被实现类继承，调用方请写全限定名。
     */
    static boolean looksExplicit(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        for (String marker : new String[]{"记住", "记一下", "帮我记", "要记得", "别忘了"}) {
            if (userText.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
