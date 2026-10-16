package com.threadfit.glucopilot.ai.memory;

import com.threadfit.glucopilot.ai.config.AIProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
    public record Assembled(List<Message> messages, Stats stats) {
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
     * @param history        候选历史（时间正序；由仓库按 max-fetch 捞回）
     * @param current        本轮消息（永不裁剪）
     * @param summary        滚动摘要，可为 null/空
     * @param toolDigests    最近工具结果摘要（可为空列表）
     * @param fixedOverhead  固定开销的估算 token（系统提示词 + 工具定义）；由调用方按上一轮真实用量或估算给出
     */
    public Assembled assemble(List<Message> history,
                              List<Message> current,
                              String summary,
                              List<String> toolDigests,
                              int fixedOverhead) {
        AIProperties.MemoryProperties config = aiProperties.getMemory();
        int budget = Math.max(config.getMaxPromptTokens(), 1);
        List<Message> safeHistory = history == null ? List.of() : history;
        List<Message> safeCurrent = current == null ? List.of() : current;

        StringBuilder context = new StringBuilder();
        boolean summaryUsed = appendSummary(context, summary, config.getSummaryMaxChars());
        boolean toolDigestUsed = appendToolDigests(context, toolDigests, config.getToolReplayMaxItems());
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
            } else {
                break;
            }
        }
        java.util.Collections.reverse(kept);

        // 去掉"历史末尾与本轮重复"的那条用户消息。
        // 背景：接口 2.24 先把用户消息落库、2.25 再开流，而记忆是从库里读的——于是本轮这条
        // 既在历史末尾、又在 current 里。不去重就会把同一句话送两遍（白花 token，还容易让模型
        // 以为用户重复问了一次）。这条规则从旧版 Advisor 的 merge(...) 原样搬过来。
        int droppedDuplicate = dropTrailingDuplicate(kept, safeCurrent);
        int keptCount = kept.size();

        List<Message> messages = new ArrayList<>(keptCount + safeCurrent.size() + 1);
        if (contextMessage != null) {
            messages.add(contextMessage);
        }
        messages.addAll(kept);
        messages.addAll(safeCurrent);
        return new Assembled(List.copyOf(messages),
                new Stats(keptCount, safeHistory.size() - keptCount - droppedDuplicate, used, budget,
                        summaryUsed, toolDigestUsed));
    }

    /**
     * 历史末尾若是本轮用户消息的副本，就从历史里去掉它（返回被去掉的条数）。
     * <p>
     * 只比对<b>文本相同</b>且角色为用户的消息：助手消息可能与历史里的某条内容相同但语义不同
     * （例如模型两次给出同样的回答），那不该被当成重复。
     */
    private static int dropTrailingDuplicate(List<Message> kept, List<Message> current) {
        if (kept.isEmpty() || current.isEmpty()) {
            return 0;
        }
        int last = kept.size() - 1;
        Message tail = kept.get(last);
        if (!(tail instanceof UserMessage tailUser) || tailUser.getText() == null) {
            return 0;
        }
        boolean duplicated = current.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(tailUser.getText()::equals);
        if (!duplicated) {
            return 0;
        }
        kept.remove(last);
        return 1;
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
