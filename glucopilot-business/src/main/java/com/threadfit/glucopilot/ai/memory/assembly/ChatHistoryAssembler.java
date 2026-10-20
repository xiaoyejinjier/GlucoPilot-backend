package com.threadfit.glucopilot.ai.memory.assembly;

import com.threadfit.glucopilot.ai.config.AIProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import com.threadfit.glucopilot.ai.prompt.advisor.DynamicPromptAdvisor;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;

/**
 * 会话历史的装配（② 期）：把"仓库里的候选历史 + 摘要 + 工具摘要 + 本轮消息"按 <b>token 预算</b>
 * 组装成最终送进模型的消息列表。
 *
 * <h2>为什么单独抽成一个纯函数组件</h2>
 * 装配规则（先扣固定开销 → 从最近往回装 → 保底条数 → 超预算丢最老的）是这一期最容易出错、
 * 也最需要被反复验证的部分。放在 Advisor 里就只能靠"起一个假 ChatModel 跑一遍"来验；
 * 抽出来之后，任何预算组合都能用纯单测覆盖（见 {@code ChatHistoryAssemblerTest}）。
 *
 * <h2>装配顺序（与最终消息顺序一致）</h2>
 * <ol>
 *   <li>调用方已经把系统提示词放在最前（{@code DynamicPromptAdvisor}），本类不动它；</li>
 *   <li>附加上下文（更早对话摘要 + 最近工具结果摘要）：作为<b>一条</b> {@link SystemMessage} 插在最前，
 *       并显式标注"不是本轮原话"，避免模型把摘要当成用户刚说的话去回应；</li>
 *   <li>历史（从老到新，可能被预算裁掉最老的部分）；</li>
 *   <li>本轮消息（调用方传入的 {@code current}，<b>永不裁剪</b>）。</li>
 * </ol>
 *
 * <h2>保底与超预算</h2>
 * 无论预算多紧，都至少保留 {@code ai.memory.keep-recent-messages} 条最近历史：
 * 只留最后一轮会让多轮追问（"那第二条呢"）直接断片，这比"超一点预算"更伤体验。
 * 因此预算是<b>安全阀</b>，不是硬上限——这一点在日志里会如实打出实际估算值。
 */
@Component
@RequiredArgsConstructor
public class ChatHistoryAssembler {

    private final AIProperties aiProperties;

    private final TokenEstimator tokenEstimator;

    /** 装配结果。 */
    public record Assembled(List<Message> messages, Stats stats, List<ChatContextShard> shards) {
    }

    /**
     * 装配统计（用于日志与 E2E 断言）。
     *
     * @param keptHistory      实际送进模型的历史条数
     * @param droppedHistory   因预算被丢掉的历史条数
     * @param estimatedTokens  本轮 prompt 的估算 token（历史 + 附加上下文 + 本轮消息）
     * @param budgetTokens     预算上限
     * @param summaryUsed      是否用了滚动摘要
     * @param toolDigestUsed   是否注入了工具结果摘要
     */
    public record Stats(int keptHistory, int droppedHistory, int estimatedTokens, int budgetTokens,
                        boolean summaryUsed, boolean toolDigestUsed) {

        public boolean budgetExceeded() {
            return estimatedTokens > budgetTokens;
        }
    }

    /**
     * 旧签名（不含长期记忆）：保留以免既有调用与测试全量改动，内部转调新签名。
     */
    public Assembled assemble(List<Message> history,
                              List<Message> current,
                              String summary,
                              List<String> toolDigests,
                              int fixedOverhead) {
        return assemble(history, current, summary, toolDigests, List.of(), fixedOverhead);
    }

    /**
     * 装配（③ 期起含长期记忆）。
     * <p>
     * {@code memories} 是该用户的**跨会话长期记忆**（忌口/目标/长期状况/习惯/偏好）。
     * 它与摘要同属"附加上下文"，但语义更重：摘要说明"这段对话聊了什么"，记忆说明"你是谁"，
     * 所以注入时明确标注"不要复述、不要据此下医嘱"，并与摘要/工具摘要合并成**同一条** system 消息。
     *
     * @param history        候选历史（时间正序；由仓库按 max-fetch 捞回）
     * @param current        本轮消息（永不裁剪）
     * @param summary        滚动摘要，可为 null/空
     * @param toolDigests    最近工具结果摘要（可为空列表）
     * @param memories       长期记忆（可为空列表；条数由读取侧限制）
     * @param fixedOverhead  固定开销的估算 token（系统提示词 + 工具定义）；由调用方按上一轮真实用量或估算给出
     */
    public Assembled assemble(List<Message> history,
                              List<Message> current,
                              String summary,
                              List<String> toolDigests,
                              List<UserChatMemoryService.MemoryItem> memories,
                              int fixedOverhead) {
        AIProperties.MemoryProperties config = aiProperties.getMemory();
        int budget = Math.max(config.getMaxPromptTokens(), 1);
        List<Message> safeHistory = history == null ? List.of() : history;
        List<Message> safeCurrent = current == null ? List.of() : current;

        // 三段附加上下文各自独立构造：既是为了拼装顺序可控，也是为了给"分片元数据"（③ 期）
        // 逐段算出估算 token。分成三个 StringBuilder 而不是边拼边估，是因为估算要按<b>整段</b>算
        // （estimate 里 CJK 与非 CJK 的比例是按字符统计的，拆开估会引入不必要的误差）。
        StringBuilder memorySection = new StringBuilder();
        StringBuilder summarySection = new StringBuilder();
        StringBuilder toolSection = new StringBuilder();
        boolean memoryUsed = appendMemories(memorySection, memories);
        boolean summaryUsed = appendSummary(summarySection, summary, config.getSummaryMaxChars());
        boolean toolDigestUsed = appendToolDigests(toolSection, toolDigests, config.getToolReplayMaxItems());

        StringBuilder context = new StringBuilder(memorySection);
        if (summarySection.length() > 0) {
            if (context.length() > 0) {
                context.append("\n\n");
            }
            context.append(summarySection);
        }
        if (toolSection.length() > 0) {
            if (context.length() > 0) {
                context.append("\n\n");
            }
            context.append(toolSection);
        }
        SystemMessage contextMessage = context.length() == 0 ? null : new SystemMessage(context.toString());

        int used = fixedOverhead;
        if (contextMessage != null) {
            used += tokenEstimator.estimateMessage(contextMessage.getText());
        }
        for (Message message : safeCurrent) {
            used += tokenEstimator.estimateMessage(textOf(message));
        }

        // 从最近往回装：只要"再装一条"不超过预算就装（并同时受条数上限约束）
        int keepRecent = Math.max(config.getKeepRecentMessages(), 0);
        int maxMessages = Math.max(config.getWindowMessages(), 1);
        int historyTokens = 0;
        List<Message> kept = new ArrayList<>();
        for (int i = safeHistory.size() - 1; i >= 0; i--) {
            Message message = safeHistory.get(i);
            int cost = tokenEstimator.estimateMessage(textOf(message));
            boolean withinCount = kept.size() < maxMessages;
            if (!withinCount) {
                break;
            }
            // 保底条数之内的消息无条件保留（预算只是安全阀）
            if (kept.size() < keepRecent || used + cost <= budget) {
                kept.add(message);
                used += cost;
                historyTokens += cost;
            } else {
                break;
            }
        }
        java.util.Collections.reverse(kept);

        // 去掉"历史末尾与本轮重复"的那条用户消息。
        // 背景：接口 2.24 先把用户消息落库、2.25 再开流，而记忆是从库里读的——于是本轮这条
        // 既在历史末尾、又在 current 里。不去重就会把同一句话送两遍（白花 token，还容易让模型
        // 以为用户重复问了一次）。这条规则从旧版 Advisor 的 merge(...) 原样搬过来。
        // 被去掉的那条不再进 prompt，分片里的历史 token 也要跟着扣掉，
        // 否则"分片之和"会比实际发出的 prompt 多出整整一条消息
        Message removedDuplicate = dropTrailingDuplicate(kept, safeCurrent);
        int droppedDuplicate = removedDuplicate == null ? 0 : 1;
        if (removedDuplicate != null) {
            historyTokens -= tokenEstimator.estimateMessage(textOf(removedDuplicate));
        }
        int keptCount = kept.size();
        List<ChatContextShard> shards = buildShards(fixedOverhead, memorySection, summarySection, toolSection,
                memoryUsed, summaryUsed, toolDigestUsed, historyTokens);

        List<Message> messages = new ArrayList<>(keptCount + safeCurrent.size() + 1);
        if (contextMessage != null) {
            messages.add(contextMessage);
        }
        messages.addAll(kept);
        messages.addAll(safeCurrent);
        return new Assembled(List.copyOf(messages),
                new Stats(keptCount, safeHistory.size() - keptCount - droppedDuplicate, used, budget,
                        summaryUsed, toolDigestUsed),
                shards);
    }

    /**
     * 本轮 prompt 的分片元数据（③ 期）：顺序固定为
     * 系统提示 → 长期记忆 → 滚动摘要 → 工具结果 → 历史消息。
     * <p>
     * <b>顺序即 {@code sort_no}</b>，与 {@code ChatContextShard} 里的常量一一对应：
     * 它表达的是"越靠前越基础"（你是谁 &gt; 聊过什么 &gt; 刚查到什么 &gt; 逐字原文）。
     * 没有用到的分片不产出行——空行只会让"这一轮到底注入了什么"更难读。
     * 历史消息片即使为 0 条也保留：它说明"这一轮几乎没有历史可回放"，
     * 而这恰恰是排查"模型为什么忘了前面"时最想看的一项。
     */
    private List<ChatContextShard> buildShards(int fixedOverhead,
                                               StringBuilder memorySection,
                                               StringBuilder summarySection,
                                               StringBuilder toolSection,
                                               boolean memoryUsed,
                                               boolean summaryUsed,
                                               boolean toolDigestUsed,
                                               int historyTokens) {
        List<ChatContextShard> shards = new ArrayList<>(5);
        shards.add(ChatContextShard.systemPrompt(fixedOverhead));
        if (memoryUsed) {
            shards.add(ChatContextShard.longTermMemory(tokenEstimator.estimate(memorySection.toString())));
        }
        if (summaryUsed) {
            shards.add(ChatContextShard.summary(tokenEstimator.estimate(summarySection.toString())));
        }
        if (toolDigestUsed) {
            shards.add(ChatContextShard.toolDigest(tokenEstimator.estimate(toolSection.toString())));
        }
        shards.add(ChatContextShard.historyMessages(historyTokens));
        return List.copyOf(shards);
    }

    /**
     * 历史末尾若是本轮用户消息的副本，就从历史里去掉它（返回被去掉的那条，没有则 null）。
     * <p>
     * 只比对<b>文本相同</b>且角色为用户的消息：助手消息可能与历史里的某条内容相同但语义不同
     * （例如模型两次给出同样的回答），那不该被当成重复。
     * <p>
     * 返回整条消息（而不是一个计数）是为了让调用方能从"历史 token 合计"里精确地减掉它——
     * 否则分片元数据会多算一条消息的量，与真实发出的 prompt 对不上。
     */
    private static Message dropTrailingDuplicate(List<Message> kept, List<Message> current) {
        if (kept.isEmpty() || current.isEmpty()) {
            return null;
        }
        int last = kept.size() - 1;
        Message tail = kept.get(last);
        if (!(tail instanceof UserMessage tailUser) || tailUser.getText() == null) {
            return null;
        }
        boolean duplicated = current.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(tailUser.getText()::equals);
        if (!duplicated) {
            return null;
        }
        kept.remove(last);
        return tail;
    }

    /**
     * 长期记忆作为附加上下文的第一段（比摘要更靠前：它是"你是谁"，比"聊过什么"更基础）。
     * <p>
     * 条数由调用方（读取侧）限制；这里再限一次长度，避免一条超长记忆把预算吃光。
     */
    private static boolean appendMemories(StringBuilder context, List<UserChatMemoryService.MemoryItem> memories) {
        if (memories == null || memories.isEmpty()) {
            return false;
        }
        context.append("【关于这位用户的长期记忆（系统记住的事实，供你参考）】\n");
        for (UserChatMemoryService.MemoryItem item : memories) {
            if (item == null || item.text() == null || item.text().isBlank()) {
                continue;
            }
            String text = item.text().trim();
            if (text.length() > 120) {
                text = text.substring(0, 120);
            }
            context.append("- ").append(item.key()).append('：').append(text).append('\n');
        }
        context.append("（这些是长期背景，不要复述给用户、不要据此下诊断或用药建议；与用户本轮说法冲突时以本轮为准）");
        return true;
    }
    /** 摘要作为附加上下文的第一段；显式标注"不是本轮原话"。 */
    private static boolean appendSummary(StringBuilder context, String summary, int maxChars) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        String text = summary.trim();
        if (text.length() > maxChars) {
            text = text.substring(text.length() - maxChars);
        }
        context.append("【更早对话的摘要（由系统压缩，不是你刚才说的话；只作为背景，不要复述）】\n")
                .append(text);
        return true;
    }

    /** 工具结果摘要：让模型能引用"上一轮查到的数据"，而不是重新调工具（B3 的后半）。 */
    private static boolean appendToolDigests(StringBuilder context, List<String> digests, int maxItems) {
        if (digests == null || digests.isEmpty() || maxItems <= 0) {
            return false;
        }
        List<String> used = digests.size() > maxItems ? digests.subList(digests.size() - maxItems, digests.size()) : digests;
        if (context.length() > 0) {
            context.append("\n\n");
        }
        context.append("【最近查询到的数据摘要（服务端记录，供你引用；不要据此给用药建议）】\n");
        for (String digest : used) {
            if (digest != null && !digest.isBlank()) {
                context.append("- ").append(digest.trim()).append('\n');
            }
        }
        return true;
    }

    private static String textOf(Message message) {
        return message.getText() == null ? "" : message.getText();
    }
}
