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
 * <h2>三个写入入口（刻意不做"每轮都抽"）</h2>
 * <ul>
 *   <li><b>模型调用 {@code save_long_term_memory} 时</b>
 *       （{@link #saveExplicit(Long, Long, String, String)}）——用户当轮亲口说的长期事实，
 *       最可信，也最快生效（下一轮就能注入）；</li>
 *   <li><b>会话结束时</b>——把整段会话复盘一遍，兜住"该记但模型没当场记"的事实；</li>
 *   <li><b>会话被闲置归档时</b>——用户再也没回来，这段对话不补抽就永久丢失。</li>
 * </ul>
 * 三条入口的<b>输入窗口与冲突策略</b>不同（即时看到一轮 / 复盘看到几十轮；前者可无条件覆盖、
 * 后者只能被不低于既有置信度的候选覆盖），但写库都走同一个 {@code upsert}：
 * 四道闸（键、长度、置信度、危险词）是红线，不能有两份实现。
 * 每轮都抽的成本是对话的两倍，噪声也大得多，所以不做。
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
     * 写入来源：决定<b>冲突时谁能覆盖谁</b>。
     * <p>
     * 为什么需要这个区分：`upsert` 是"同键覆盖"，谁最后写谁赢。而三条入口的可信度并不相同——
     * 用户当轮亲口说的（"我不吃香菜了"）应当盖掉任何历史推断；反过来，一段三天前的老会话
     * 在归档补齐时抽出的"用户吃香菜"，绝不能盖掉用户今天刚说的新版。把差异放在这个枚举上，
     * 而不是复制两套写库代码，是为了让四道闸只有一处实现。
     */
    enum WriteSource {
        /** 用户当轮亲口要求记住：无条件覆盖（即使旧值置信度更高）。 */
        EXPLICIT,
        /** 会话结束 / 归档补齐的批量复盘：只在置信度不低于既有值时才覆盖。 */
        BATCH
    }

    /**
     * 一次显式写入的结果，同时也是给模型的回执。
     * <p>
     * 拒绝时 {@code reason} 会被模型看到，所以措辞要能指导它的下一步（例如"键不在允许范围"而不是
     * "写入失败"）——模型看到"键不合法、只能是这五类"才知道该换个键重试，看到"失败"只会重试同样的东西。
     */
    record SaveOutcome(boolean saved, String reason) {

        public static SaveOutcome ok() {
            return new SaveOutcome(true, "已记录");
        }

        public static SaveOutcome rejected(String reason) {
            return new SaveOutcome(false, reason);
        }
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
     * 用户自助删除<b>某一个键</b>的记忆（记忆页每张卡片的「删除」）。
     *
     * <p>为什么在"全部清空"之外还要有单条删除：用户往往只错记了其中一条（"我不吃香菜"其实是不吃葱），
     * 而清空会连带删掉四条正确的记忆，他反而要重新讲一遍。粒度按 {@code memoryKey} 而不是内部主键，
     * 见 {@code UserChatMemoryDtos} 的字段取舍。
     *
     * <p>删除是逻辑删除 + 写入侧冷却（{@code ai.memory.deleted-key-cooldown-days}）：
     * 否则会话结束时的自动抽取会把同一个事实从原文里再读出来、再插回去。
     *
     * @return 1 = 删掉了；0 = 本来就没有这条（幂等）
     * @throws com.threadfit.glucopilot.common.exception.BizException 键不在白名单里时抛 400
     */
    int deleteByKeyForUser(Long userId, String memoryKey);

    /**
     * 账号注销：物理删除该用户全部长期记忆。
     * <p>
     * 与 {@link #deleteAllForUser} 的区别是"逻辑删除 vs 物理删除"：注销后不能留行，
     * 否则系统仍然"记得"这个已经不存在的人。
     */
    void purgeAccountData(Long userId);

    /**
     * 用户当轮显式要求记住一条长期事实（{@code save_long_term_memory} 工具的唯一入口）。
     * <p>
     * 与 {@link #extractAsync} 的区别：这里只写<b>模型明确指定的一条</b>，不读会话、不调模型，
     * 因此可以同步返回结果给模型；置信度由服务端固定（用户亲口说的就是高可信，不需要模型自评）。
     * <p>
     * <b>不抛异常</b>：工具调用不该因为一次写入失败而打断整轮对话，失败一律折成
     * {@link SaveOutcome#rejected(String)} 让模型知道"没记住"。
     *
     * @param sourceSessionId 来源会话（可空：无会话上下文时不记来源）
     */
    SaveOutcome saveExplicit(Long userId, Long sourceSessionId, String key, String text);

    /**
     * 异步抽取某个会话的长期记忆。
     * <p>
     * <b>不阻塞、不抛异常</b>：单飞（同一会话同时只跑一个抽取任务），失败只记日志——
     * 抽取是增强项，绝不能因为一次抽取失败影响已经成功的回复或刚建好的会话。
     *
     * @param reason 触发原因（{@code session-ended} / {@code session-archived} /
     *               {@code explicit}（仅无工具部署的兜底）），只用于日志与窗口选择
     *               <p>
     *               {@code explicit} 受 {@code ai.memory.keyword-trigger-fallback} 控制：默认关闭，
     *               打开时只回看 {@code ai.memory.keyword-fallback-window-messages} 条
     *               （默认 6）——它由聊天主链路顺带触发，不能像终局复盘那样一次回看整段
     */
    void extractAsync(Long userId, Long sessionId, String reason);

    /** 当前是否有抽取任务在跑（E2E/断言用）。 */
    boolean inFlight();

    /**
     * 用户消息里是否含"记住…"这类显式要求。
     * <p>
     * <b>只作为兜底</b>：正常情况下"要不要记"由模型判断并调用 {@code save_long_term_memory}，
     * 关键词枚举天生漏（"我最近不能吃辣了""医生让我少油"都不含"记住"），只适合在
     * <b>没有 tool calling 的部署</b>里当降级手段，由 {@code ai.memory.keyword-trigger-fallback} 控制。
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
