package com.threadfit.glucopilot.ai.memory.service;

import com.threadfit.glucopilot.ai.memory.dto.UserChatMemoryDtos;
import com.threadfit.glucopilot.common.api.PageQuery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    /**
     * 五类的<b>固定展示顺序</b>：忌口 → 目标 → 健康状况 → 习惯 → 偏好。
     *
     * <p>为什么不能只用一个 {@code Set}：{@code Set.of(...)} 的迭代顺序在不同 JVM/不同次启动上并不承诺稳定，
     * 而注入顺序会直接决定 system 消息里记忆段的字节排列——顺序一变，这一段之后的所有历史都不再命中
     * 前缀缓存（DeepSeek 的缓存是前缀匹配，实测命中与未命中差 30 倍价）。所以顺序必须是显式常量。
     */
    List<String> ALLOWED_KEY_ORDER =
            List.of("diet_taboo", "glucose_goal", "health_context", "habit", "preference");

    /**
     * 送入上下文的一类记忆：一个键 + 该类下的若干条目。
     *
     * <p>为什么不是「一条记忆 = 一行文本 + 一个置信度」：V71 之后同一类可以有多条（"不吃香菜"与"海鲜过敏"
     * 都是忌口），而置信度是<b>条目</b>的属性，不是类别的属性。早先把同类条目拼成一行再用 max 聚合成
     * 一个置信度，等于让一条高置信度的条目给整行背书——模型无从知道"海鲜过敏(90)"与"不吃香菜(62)"
     * 的可信程度并不相同。结构化的代价只是多一层嵌套，换来的是可以逐条判断"要不要先跟用户确认"。
     *
     * @param key     五类之一（{@link #ALLOWED_KEY_ORDER}）
     * @param entries 该类下的条目，按置信度降序、最近更新降序，最多 {@code ai.memory.inject-max-items-per-key} 条
     */
    record MemoryItem(String key, List<Entry> entries) {

        /** 紧凑构造器：防止调用方传 null 列表，也顺手做成不可变视图。 */
        public MemoryItem {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        /** 该类实际要注入的条目数（装配器按它累计条数上限）。 */
        public int count() {
            return entries.size();
        }

        /** 该类里最低的条目置信度；空列表返回 0（只用于日志，不参与过滤）。 */
        public int minConfidence() {
            return entries.stream().mapToInt(Entry::confidence).min().orElse(0);
        }

        /** 该类里最高的条目置信度；空列表返回 0（只用于日志）。 */
        public int maxConfidence() {
            return entries.stream().mapToInt(Entry::confidence).max().orElse(0);
        }

        /**
         * 一条具体的事实。
         *
         * @param text       事实原文（已 trim，长度受 {@code MAX_TEXT_CHARS} 约束）
         * @param confidence 0~100：用户亲口说的通常 85+，模型抽取的按提示词给的分数
         */
        public record Entry(String text, int confidence) {
        }
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
     * 读工具（{@code list_long_term_memory}）单次最多返回的条数。
     * <p>
     * 放在接口上是让「工具层的默认值/上限」与「服务层的硬夹紧」共用同一个数字，
     * 避免两处各写一个 30、将来改一处漏一处。
     */
    int MAX_MODEL_ITEMS = 30;

    /** 给模型看的一条记忆：带更新时间（用于"上次你说"），<b>不带</b>置信度数值与主键。 */
    record MemoryEntry(String key, String text, LocalDateTime updatedAt) {
    }

    /**
     * 读工具的一次性视图。
     *
     * <p>为什么把 {@code total} 和 {@code items} 分开：注入侧最大的毛病是模型"看到 15 条就以为总共 15 条"。
     * 这里显式给出「一共几条、这次给了几条、还有没有更多」，它才能对用户说清"我记着 27 条，最相关的先说"，
     * 而不是拿 15 条去猜剩下的不存在。
     *
     * @param total     当前筛选条件下的有效条数（无筛选时等于五类之和）
     * @param counts    五类各自的有效条数（不受筛选影响，零值也出现）
     * @param items     本次返回的条目，已按「置信度降序、最近更新降序」排好
     * @param truncated 是否还有更多匹配（{@code total > items.size()}）
     */
    record MemoryOverview(int total, Map<String, Integer> counts, List<MemoryEntry> items, boolean truncated) {
    }

    /**
     * 供读工具使用的总览：可按类与关键词收窄，条数受 {@link #MAX_MODEL_ITEMS} 限制。
     *
     * <p>与 {@link #forInjection} 的区别：注入侧要的是"每次都塞进 prompt 的最可信几条"（有预算约束），
     * 这里要的是"模型主动问了才取"的视图（有筛选、有总量、条数更多）。两者排序口径必须一致，
     * 否则模型从两条路径看到同一批记忆却得出不同的重要度判断。
     *
     * <p>读失败照常抛出：工具会把异常翻译成结构化错误，而不是悄悄返回"你没有任何记忆"。
     *
     * @param memoryKey 只看某一类；空白表示不限类，非白名单值抛 400
     * @param keyword   正文子串（已由调用方 trim 与长度校验）；空白表示不筛
     * @param limit     本次最多返回几条（调用方负责夹紧，服务端再按 {@link #MAX_MODEL_ITEMS} 兜底）
     */
    MemoryOverview overviewForModel(Long userId, String memoryKey, String keyword, int limit);

    /**
     * 用户自己看自己的记忆（C 端合规自助）：<b>分页</b>返回有效条目，不按注入上限截断。
     * <p>
     * 为什么必须分页：类别是有界的（五类），但<b>条目不再有界</b>——「饮食忌口」下可以同时记着
     * 不吃香菜、不吃辣、海鲜过敏……重度用户一年下来可能攒到几百条。一次全量返回既拖慢首屏，
     * 也会让"系统到底记了我什么"这个问题变成一屏滚不到底的列表。
     * <p>
     * 读失败<b>照常抛出</b>——"系统到底记住了我什么"这个问题不能用空列表糊弄过去。
     *
     * @param memoryKey 只看某一类（记忆页点开某个分类入口时传）；空白表示不限类。
     *                  不在五类白名单里抛 400——静默当成"不限"会让页面看起来正常、
     *                  实际显示的是全部记忆，用户以为这一类是空的或乱的。
     * @return 当前页 + 全局的每类条数（{@code counts} 不受 {@code memoryKey} 影响，
     *         供顶部分类入口一直显示真实条数）
     */
    UserChatMemoryDtos.MemoryPage listOwnForUser(Long userId, String memoryKey, PageQuery query);

    /** 用户自助删除全部长期记忆（合规：用户有权清掉系统对他的记忆）。返回删除条数。 */
    int deleteAllForUser(Long userId);

    /**
     * 用户自助删除<b>某一个类别下的全部条目</b>（记忆页每类卡片上的「清空本类」）。
     *
     * <p>与 {@link #deleteByIdForUser} 的分工：用户说"忌口别记了"用这个，说"删掉香菜那条"用那个。
     * 少了前者，用户要把三类忌口一条条点掉；少了后者，用户点一条却连带删掉另外两条。
     *
     * <p>删除是逻辑删除 + 写入侧条目级冷却（{@code ai.memory.deleted-key-cooldown-days}）：
     * 否则会话结束时的自动抽取会把同一句话从原文里再读出来、再插回去。
     *
     * @return 实际删除条数；0 = 这一类本来就没有生效条目（幂等）
     * @throws com.threadfit.glucopilot.common.exception.BizException 键不在白名单里时抛 400
     */
    int deleteByKeyForUser(Long userId, String memoryKey);

    /**
     * 用户自助删除<b>一个条目</b>（记忆页每张卡片的「删除」）。
     *
     * @param id 条目主键（来自 {@link #listOwnForUser}）；服务端仍会带 {@code user_id} 过滤，
     *           拿到别人的 id 只会删到 0 行
     * @return 1 = 删掉了；0 = 本来就没有这条（幂等，含"这个 id 不属于你"）
     */
    int deleteByIdForUser(Long userId, Long id);

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
