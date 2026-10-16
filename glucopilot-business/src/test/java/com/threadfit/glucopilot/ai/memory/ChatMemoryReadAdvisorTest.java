package com.threadfit.glucopilot.ai.memory;

import com.threadfit.glucopilot.ai.config.AIProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆注入（② 期：token 预算装配 + 摘要/工具摘要注入 + 去重）。
 * <p>
 * 这里断言的是<b>顾问层真正注入 prompt 的消息列表</b>——"配置是否生效"的唯一判据。
 * 装配规则本身（预算、保底、附件）在 {@code ChatHistoryAssemblerTest} 里用纯函数覆盖，
 * 本类负责"接线是否正确"：
 * <ol>
 *   <li>会话 ID → 装哪条会话的历史；</li>
 *   <li>固定开销与估算回写槽通过 advisor 参数传递（不能靠 ThreadLocal：回调不在请求线程上）；</li>
 *   <li>历史末尾与本轮重复的用户消息要被去掉（2.24 先落库、2.25 再开流，必然重复一次）。</li>
 * </ol>
 */
class ChatMemoryReadAdvisorTest {

    private static final long SESSION_ID = 9L;

    private AIProperties properties;

    private ChatHistoryAssembler assembler;

    private ChatContextReader contextReader;

    @BeforeEach
    void setUp() {
        properties = new AIProperties();
        assembler = new ChatHistoryAssembler(properties, new TokenEstimator());
        contextReader = mock(ChatContextReader.class);
    }

    private ChatMemoryReadAdvisor advisor(ChatMemory chatMemory) {
        return new ChatMemoryReadAdvisor(chatMemory, assembler, contextReader);
    }

    @Test
    void 顾问注入的历史条数不应超过窗口配置() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(messages(1, 80));
        when(contextReader.summary(SESSION_ID)).thenReturn(null);
        when(contextReader.recentToolDigests(SESSION_ID)).thenReturn(List.of());

        List<Message> injected = injectedMessages(advisor(chatMemory), new UserMessage("那我晚饭呢"));

        assertEquals(21, injected.size(), "窗口 20 条历史 + 本轮 1 条");
        assertEquals("m60", injected.get(0).getText(), "应保留最近 20 条历史");
        assertEquals("那我晚饭呢", injected.get(20).getText());
    }

    @Test
    void 窗口内历史不足时全部注入() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(messages(1, 4));   // 3 条
        when(contextReader.recentToolDigests(SESSION_ID)).thenReturn(List.of());

        List<Message> injected = injectedMessages(advisor(chatMemory), new UserMessage("你好"));

        assertEquals(4, injected.size(), "3 条历史 + 本轮 1 条");
    }

    @Test
    void 历史末尾与本轮用户消息重复时应去重() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("早餐前血糖 6.8 正常吗"));
        history.add(new AssistantMessage("在正常范围内……"));
        history.add(new UserMessage("那我晚饭呢"));   // 本轮这条已经落库，历史末尾会带上它
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(history);
        when(contextReader.recentToolDigests(SESSION_ID)).thenReturn(List.of());

        List<Message> injected = injectedMessages(advisor(chatMemory), new UserMessage("那我晚饭呢"));

        assertEquals(List.of("早餐前血糖 6.8 正常吗", "在正常范围内……", "那我晚饭呢"),
                texts(injected), "同一句话不能送两遍");
    }

    @Test
    void 历史末尾是助手消息时不去重() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("那我晚饭呢"));
        history.add(new AssistantMessage("晚饭建议……"));
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(history);
        when(contextReader.recentToolDigests(SESSION_ID)).thenReturn(List.of());

        // 文本相同但角色不同（助手说过同样的话），不该当成重复删掉
        List<Message> injected = injectedMessages(advisor(chatMemory), new UserMessage("晚饭建议……"));

        assertEquals(3, injected.size());
    }

    @Test
    void 摘要与工具摘要作为附加上下文注入且标注不是原话() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(messages(1, 3));
        when(contextReader.summary(SESSION_ID)).thenReturn("用户早餐前血糖 6.8，希望餐后控制到 8 以内");
        when(contextReader.recentToolDigests(SESSION_ID))
                .thenReturn(List.of("query_glucose_range → {avg:6.8,count:5}"));

        List<Message> injected = injectedMessages(advisor(chatMemory), new UserMessage("那我晚饭呢"));

        assertTrue(injected.get(0) instanceof SystemMessage, "附加上下文必须是 system 消息，放在历史之前");
        String context = injected.get(0).getText();
        assertTrue(context.contains("更早对话的摘要"), "摘要要标注来源：" + context);
        assertTrue(context.contains("不是你刚才说的话"), "摘要要明确不是原话，避免模型当成用户新说的：" + context);
        assertTrue(context.contains("最近查询到的数据摘要"), "工具摘要要单独标注：" + context);
        assertEquals("那我晚饭呢", injected.get(injected.size() - 1).getText());
    }

    @Test
    void 预算紧张时仍保留最近若干条且不丢本轮消息() {
        properties.getMemory().setMaxPromptTokens(60);   // 极小预算
        properties.getMemory().setKeepRecentMessages(4);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(messages(1, 20));
        when(contextReader.recentToolDigests(SESSION_ID)).thenReturn(List.of());

        List<Message> injected = injectedMessages(advisor(chatMemory), new UserMessage("本轮问题"));

        assertTrue(injected.size() >= 5, "保底 4 条历史 + 本轮至少 1 条，实际=" + injected.size());
        assertEquals("本轮问题", injected.get(injected.size() - 1).getText(), "本轮消息永不被裁");
        assertTrue(texts(injected).contains("m19"), "最近的历史必须保住");
    }

    @Test
    void 估算会写回调用方持有的槽位() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(messages(1, 3));
        when(contextReader.recentToolDigests(SESSION_ID)).thenReturn(List.of());
        AtomicInteger slot = new AtomicInteger();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new UserMessage("你好"))),
                Map.of(ChatMemory.CONVERSATION_ID, String.valueOf(SESSION_ID),
                        ChatMemoryAdvisorParams.FIXED_OVERHEAD_TOKENS, 1000,
                        ChatMemoryAdvisorParams.PROMPT_ESTIMATE, slot));

        advisor(chatMemory).withMemoryForTest(request);

        assertTrue(slot.get() > 1000, "估算应含固定开销并写回槽位，实际=" + slot.get());
    }

    @Test
    void 未传会话ID时不注入任何历史() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatMemoryReadAdvisor advisor = advisor(chatMemory);
        List<ChatClientRequest> captured = new ArrayList<>();

        advisor.adviseCall(new ChatClientRequest(new Prompt(List.of(new UserMessage("你好"))), Map.of()),
                captureChain(captured));

        assertEquals(1, captured.get(0).prompt().getInstructions().size());
    }

    // ---------- 辅助 ----------

    private static List<Message> injectedMessages(ChatMemoryReadAdvisor advisor, Message currentMessage) {
        List<ChatClientRequest> captured = new ArrayList<>();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(currentMessage)),
                Map.of(ChatMemory.CONVERSATION_ID, String.valueOf(SESSION_ID)));

        advisor.adviseCall(request, captureChain(captured));

        assertTrue(!captured.isEmpty(), "顾问应把请求继续往下传");
        return captured.get(0).prompt().getInstructions();
    }

    private static CallAdvisorChain captureChain(List<ChatClientRequest> captured) {
        return new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest request) {
                captured.add(request);
                return ChatClientResponse.builder().build();
            }

            @Override
            public List<CallAdvisor> getCallAdvisors() {
                return List.of();
            }

            @Override
            public CallAdvisorChain copy(CallAdvisor advisor) {
                return this;
            }
        };
    }

    /** 造 from..to-1 条 user 消息（左闭右开），文本为 m{序号}，便于断言"保留的是哪一段"。 */
    private static List<Message> messages(int from, int to) {
        List<Message> list = new ArrayList<>();
        for (int i = from; i < to; i++) {
            list.add(new UserMessage("m" + i));
        }
        return list;
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).collect(Collectors.toList());
    }
}
