package com.threadfit.glucopilot.ai.memory;

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
}
