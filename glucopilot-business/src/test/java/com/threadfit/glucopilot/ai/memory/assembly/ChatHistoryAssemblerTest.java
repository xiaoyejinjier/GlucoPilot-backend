package com.threadfit.glucopilot.ai.memory.assembly;

import com.threadfit.glucopilot.ai.config.AIProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.threadfit.glucopilot.ai.memory.assembly.ChatContextShard;
import com.threadfit.glucopilot.ai.memory.assembly.ChatHistoryAssembler;
import com.threadfit.glucopilot.ai.memory.assembly.TokenEstimator;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;

/**
 * 历史装配（② 期核心）。
 * <p>
 * 这是"模型到底看得到多少、看到什么"的唯一决定点，因此规则要逐条钉住：
 * <ol>
 *   <li>预算是安全阀：先扣固定开销，再从最近往回装；</li>
 *   <li>保底条数优先于预算（只留最后一轮会让多轮追问断片）；</li>
 *   <li>本轮消息<b>永远</b>不裁（裁了模型就看不见当前问题）；</li>
 *   <li>条数上限与预算取小（防估算被极短消息带偏）；</li>
 *   <li>摘要与工具摘要作为一条 system 消息插在最前，并明确标注"不是原话"。</li>
 * </ol>
 */
class ChatHistoryAssemblerTest {

    private final AIProperties properties = new AIProperties();

    private final TokenEstimator estimator = new TokenEstimator();

    private final ChatHistoryAssembler assembler = new ChatHistoryAssembler(properties, estimator);

    private static List<Message> history(int count, int charsPerMessage) {
        List<Message> messages = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < charsPerMessage; i++) {
            body.append('字');
        }
        for (int i = 0; i < count; i++) {
            messages.add(new UserMessage("m" + i + body));
        }
        return messages;
    }

    private static List<String> heads(List<Message> messages) {
        List<String> heads = new ArrayList<>();
        for (Message message : messages) {
            String text = message.getText();
            heads.add(text.length() > 4 ? text.substring(0, 4) : text);
        }
        return heads;
    }

    @Test
    void 条数上限生效时保留最近若干条() {
        properties.getMemory().setWindowMessages(5);
        properties.getMemory().setMaxPromptTokens(1_000_000);

        ChatHistoryAssembler.Assembled result = assembler.assemble(
                history(20, 2), List.of(new UserMessage("本轮")), null, List.of(), 0);

        assertEquals(6, result.messages().size(), "5 条历史 + 本轮 1 条");
        assertEquals("m15", result.messages().get(0).getText().substring(0, 3));
        assertEquals(15, result.stats().droppedHistory());
        assertFalse(result.stats().budgetExceeded());
    }

    @Test
    void 固定开销吃掉的预算会挤掉更早的历史() {
        properties.getMemory().setWindowMessages(50);
        properties.getMemory().setMaxPromptTokens(200);
        properties.getMemory().setKeepRecentMessages(1);

        // 固定开销 150，每条消息约 20 token：只剩约 2 条的空间
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                history(10, 18), List.of(new UserMessage("本轮")), null, List.of(), 150);

        assertTrue(result.stats().keptHistory() <= 3,
                "固定开销必须先扣掉，实际保留=" + result.stats().keptHistory());
        assertTrue(result.stats().keptHistory() >= 1);
    }

    @Test
    void 保底条数优先于预算() {
        properties.getMemory().setMaxPromptTokens(10);      // 极小预算
        properties.getMemory().setKeepRecentMessages(6);

        ChatHistoryAssembler.Assembled result = assembler.assemble(
                history(20, 40), List.of(new UserMessage("本轮")), null, List.of(), 0);

        assertEquals(6, result.stats().keptHistory(), "保底 6 条必须留下，即使超预算");
        assertTrue(result.stats().budgetExceeded(), "超预算要如实反映在统计里（日志会标注）");
    }

    @Test
    void 本轮消息永远保留() {
        properties.getMemory().setMaxPromptTokens(1);
        properties.getMemory().setKeepRecentMessages(0);

        ChatHistoryAssembler.Assembled result = assembler.assemble(
                history(5, 50), List.of(new UserMessage("本轮问题")), null, List.of(), 0);

        assertEquals("本轮问题", result.messages().get(result.messages().size() - 1).getText());
    }

    @Test
    void 历史末尾与本轮重复的用户消息被去掉() {
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                List.of(new UserMessage("早餐前血糖 6.8 正常吗"), new AssistantMessage("在正常范围内……"),
                        new UserMessage("那我晚饭呢")),
                List.of(new UserMessage("那我晚饭呢")), null, List.of(), 0);

        assertEquals(List.of("早餐前血糖 6.8 正常吗", "在正常范围内……", "那我晚饭呢"),
                result.messages().stream().map(Message::getText).toList());
        assertEquals(0, result.stats().droppedHistory(), "去掉的那条是重复项，不该算作'被预算裁掉'");
    }

    @Test
    void 摘要与工具摘要作为一条系统消息插在最前() {
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                List.of(new UserMessage("第一条")),
                List.of(new UserMessage("本轮")),
                "用户早餐前 6.8，目标餐后 8 以内",
                List.of("query_glucose_range → {avg:6.8}", "get_health_profile → {age:52}"),
                100);

        Message head = result.messages().get(0);
        assertInstanceOf(SystemMessage.class, head);
        String context = head.getText();
        assertTrue(context.contains("更早对话的摘要"));
        assertTrue(context.contains("不是你刚才说的话"));
        assertTrue(context.contains("最近查询到的数据摘要"));
        assertTrue(context.contains("get_health_profile"), "工具摘要要带上工具名，便于模型知道数据来源");
        assertTrue(result.stats().summaryUsed());
        assertTrue(result.stats().toolDigestUsed());
        // 历史与本轮顺序不变
        assertEquals("第一条", result.messages().get(1).getText());
        assertEquals("本轮", result.messages().get(2).getText());
    }

    @Test
    void 工具摘要只留最近若干条() {
        properties.getMemory().setToolReplayMaxItems(2);

        ChatHistoryAssembler.Assembled result = assembler.assemble(
                List.of(), List.of(new UserMessage("本轮")), null,
                List.of("t1 → a", "t2 → b", "t3 → c"), 0);

        String context = result.messages().get(0).getText();
        assertFalse(context.contains("t1 → a"), "超出条数上限的旧摘要不该注入");
        assertTrue(context.contains("t2 → b"));
        assertTrue(context.contains("t3 → c"));
    }

    @Test
    void 没有附件时不注入空的系统消息() {
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                List.of(new UserMessage("第一条")), List.of(new UserMessage("本轮")), "  ", List.of(), 0);

        assertEquals(2, result.messages().size());
        assertInstanceOf(UserMessage.class, result.messages().get(0));
        assertFalse(result.stats().summaryUsed());
    }

    @Test
    void 超长摘要按上限从尾部截断() {
        properties.getMemory().setSummaryMaxChars(10);

        ChatHistoryAssembler.Assembled result = assembler.assemble(
                List.of(), List.of(new UserMessage("本轮")), "0123456789ABCDEFGHIJ", null, 0);

        String context = result.messages().get(0).getText();
        assertTrue(context.contains("ABCDEFGHIJ"), "应保留最新的部分（尾部）");
        assertFalse(context.contains("0123456789A"), "超长部分按上限截掉");
    }

    @Test
    void 空历史时只有本轮消息() {
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                List.of(), List.of(new UserMessage("你好")), null, List.of(), 0);

        assertEquals(List.of("你好"), heads(result.messages()));
        assertEquals(0, result.stats().keptHistory());
    }

    // ---------------------------------------------------------------- ③ 期：分片元数据

    /**
     * 五类分片的顺序、类型码与 {@code sort_no} 必须固定。
     * <p>
     * 这不是美观问题：{@code chat_context} 的读取侧（排障/成本分析）按
     * {@code (session_id, context_round, sort_no)} 取一段，顺序一旦飘，
     * "这一轮 prompt 由哪些块组成"就变成一团乱序的行。类型码还要落在
     * DDL 的 {@code chk_cc_type(1..6)} 允许范围内，否则插入会被数据库拒绝。
     */
    @Test
    void 分片元数据按固定顺序给出五类() {
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                history(3, 10),
                List.of(new UserMessage("我这周血糖怎么样")),
                "用户早餐前 6.8，目标餐后 8 以内",
                List.of("query_glucose_range → {avg:6.8}"),
                List.of(new UserChatMemoryService.MemoryItem("diet_taboo", "对花生过敏", 95)),
                500);

        List<ChatContextShard> shards = result.shards();
        assertEquals(List.of("system_prompt", "long_term_memory", "context_summary", "tool_digest",
                        "history_messages"),
                shards.stream().map(ChatContextShard::contextKey).toList(), "顺序即 sort_no：越靠前越基础");
        assertEquals(List.of(1, 6, 3, 5, 6),
                shards.stream().map(ChatContextShard::contextType).toList(),
                "长期记忆与历史消息落 6（其他）：DDL 的六类里没有为它们留位置");
        assertEquals(List.of(0, 1, 2, 3, 4), shards.stream().map(ChatContextShard::sortNo).toList());
        assertEquals(500, shards.get(0).tokenEstimate(), "系统提示片用固定开销（含工具定义与消息骨架）");
        for (ChatContextShard shard : shards) {
            assertTrue(shard.tokenEstimate() >= 0, "token 估算不允许为负：" + shard);
        }
        // 有记忆/摘要/工具摘要时，它们的 token 都应为正数（内容确实进了 prompt）
        assertTrue(shards.get(1).tokenEstimate() > 0);
        assertTrue(shards.get(2).tokenEstimate() > 0);
        assertTrue(shards.get(3).tokenEstimate() > 0);
    }

    /** 没用到附加上下文时不产出行：空行只会让"这一轮注入了什么"更难读。 */
    @Test
    void 未使用的分片不产出行() {
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                history(2, 10), List.of(new UserMessage("你好")), null, List.of(), 100);

        assertEquals(List.of("system_prompt", "history_messages"),
                result.shards().stream().map(ChatContextShard::contextKey).toList());
    }

    /** 历史为空时历史分片仍保留（值为 0）：它正好说明"这一轮几乎没有历史可回放"。 */
    @Test
    void 历史为空时历史分片仍然存在且为零() {
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                List.of(), List.of(new UserMessage("你好")), null, List.of(), 0);

        ChatContextShard historyShard = result.shards().get(result.shards().size() - 1);
        assertEquals(ChatContextShard.KEY_HISTORY_MESSAGES, historyShard.contextKey());
        assertEquals(0, historyShard.tokenEstimate());
    }

    /**
     * 历史片 token 只算<b>真正送进 prompt</b>的那些消息。
     * <p>
     * 两条容易算错的边界：① 被预算裁掉的历史不算；② 末尾与本轮重复的那条被去重后，
     * 也不能再算进分片——否则"分片之和"会比实际发出的 prompt 多出整整一条消息，
     * 而分片元数据的唯一用途就是让人相信这个合计。
     */
    @Test
    void 历史分片的token只含实际送进prompt的消息() {
        List<org.springframework.ai.chat.messages.Message> history = List.of(
                new UserMessage("第一条历史消息"),
                new AssistantMessage("第一条助手回复"),
                new UserMessage("那我晚饭呢"));
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                history, List.of(new UserMessage("那我晚饭呢")), null, List.of(), 0);

        // 去重后只剩前两条进 prompt
        assertEquals(2, result.stats().keptHistory());
        int expected = estimator.estimateMessage("第一条历史消息") + estimator.estimateMessage("第一条助手回复");
        assertEquals(expected, result.shards().get(result.shards().size() - 1).tokenEstimate(),
                "被去重的那条不能算进历史分片");
    }

    /** 分片合计不得超过整轮估算（分片是同一份预算的拆解，不是额外开销）。 */
    @Test
    void 分片合计不超过整轮估算() {
        ChatHistoryAssembler.Assembled result = assembler.assemble(
                history(5, 20),
                List.of(new UserMessage("我这周血糖怎么样，需要注意什么")),
                "摘要正文若干",
                List.of("query_glucose_range → {avg:6.8}"),
                List.of(new UserChatMemoryService.MemoryItem("diet_taboo", "对花生过敏", 95)),
                300);

        int shardSum = result.shards().stream().mapToInt(ChatContextShard::tokenEstimate).sum();
        assertTrue(shardSum <= result.stats().estimatedTokens(),
                "分片合计 " + shardSum + " 不应超过整轮估算 " + result.stats().estimatedTokens());
    }
}
