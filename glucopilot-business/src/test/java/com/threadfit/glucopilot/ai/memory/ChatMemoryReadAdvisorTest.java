package com.threadfit.glucopilot.ai.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆注入的两件事：<b>窗口裁剪</b>与<b>合并去重</b>。
 * <p>
 * 裁剪这组用例是 2026-10-12 补的：此前断言"窗口由框架的 {@code MessageWindowChatMemory} 负责"，
 * 而框架的 {@code get()} 实际是纯透传（裁剪只在 {@code add()} 路径，本项目不走），
 * 于是 {@code window-messages=20} 形同虚设、每轮实际送进模型的是 {@code max-fetch=200} 条。
 * 所以这里既测静态裁剪函数，也测<b>顾问层</b>真正注入 prompt 的条数——
 * 后者才是"配置是否生效"的唯一判据。
 * <p>
 * 去重那组对应另一个坑：调用补全接口之前本轮用户消息<b>已经落库</b>（2.24 先写消息、2.25 再开流），
 * 历史末尾会带上它；请求里也带同一条时直接拼接就会把同一句话送两遍。
 */
class ChatMemoryReadAdvisorTest {

    private static final long SESSION_ID = 9L;

    // ---------- 窗口裁剪：静态函数 ----------

    @Test
    void 历史超过窗口时只保留最近若干条() {
        List<Message> history = messages(1, 25);

        List<Message> kept = ChatMemoryReadAdvisor.keepLastWindow(history, 20);

        assertEquals(20, kept.size());
        // 保留的是"最近"，即末尾，不是开头
        assertEquals("m5", kept.get(0).getText());
        assertEquals("m24", kept.get(19).getText());
    }

    @Test
    void 历史不超窗口时原样返回() {
        List<Message> history = messages(1, 6);   // m1..m5，共 5 条

        assertEquals(5, ChatMemoryReadAdvisor.keepLastWindow(history, 20).size());
    }

    @Test
    void 窗口配置非法时不裁剪而不是静默清空历史() {
        List<Message> history = messages(1, 6);   // 5 条

        // window<=0 属于配错：构造器会 WARN；这里的行为选择是"不裁剪"，
        // 因为静默把历史清空比带上过多历史更难排查
        assertEquals(5, ChatMemoryReadAdvisor.keepLastWindow(history, 0).size());
        assertEquals(5, ChatMemoryReadAdvisor.keepLastWindow(history, -1).size());
    }

    // ---------- 窗口裁剪：顾问层（真正决定送进 prompt 的条数）----------

    @Test
    void 顾问注入的历史条数不应超过窗口配置() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(messages(1, 80));
        ChatMemoryReadAdvisor advisor = new ChatMemoryReadAdvisor(chatMemory, 20);

        List<Message> injected = injectedMessages(advisor, new UserMessage("那我晚饭呢"));

        assertEquals(21, injected.size(), "窗口 20 条历史 + 本轮 1 条");
        assertEquals("m60", injected.get(0).getText(), "应保留最近 20 条历史");
        assertEquals("那我晚饭呢", injected.get(20).getText());
    }

    @Test
    void 窗口内历史不足时全部注入() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(messages(1, 4));   // 3 条
        ChatMemoryReadAdvisor advisor = new ChatMemoryReadAdvisor(chatMemory, 20);

        List<Message> injected = injectedMessages(advisor, new UserMessage("你好"));

        assertEquals(4, injected.size(), "3 条历史 + 本轮 1 条");
    }

    @Test
    void 未传会话ID时不注入任何历史() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatMemoryReadAdvisor advisor = new ChatMemoryReadAdvisor(chatMemory, 20);
        List<ChatClientRequest> captured = new ArrayList<>();

        advisor.adviseCall(new ChatClientRequest(new Prompt(List.of(new UserMessage("你好"))), Map.of()),
                captureChain(captured));

        assertEquals(1, captured.get(0).prompt().getInstructions().size());
    }

    // ---------- 合并与去重 ----------

    @Test
    void 历史末尾与本轮用户消息重复时应去重() {
        List<Message> history = List.of(
                new UserMessage("早餐前血糖 6.8 正常吗"),
                new AssistantMessage("在正常范围内……"),
                new UserMessage("那我晚饭呢"));
        List<Message> current = List.of(new UserMessage("那我晚饭呢"));

        assertEquals(List.of("早餐前血糖 6.8 正常吗", "在正常范围内……", "那我晚饭呢"),
                texts(ChatMemoryReadAdvisor.merge(history, current)));
    }

    @Test
    void 历史末尾与本轮不同时应完整拼接() {
        List<Message> history = List.of(
                new UserMessage("早餐前血糖 6.8 正常吗"),
                new AssistantMessage("在正常范围内……"));
        List<Message> current = List.of(new UserMessage("那我晚饭呢"));

        assertEquals(List.of("早餐前血糖 6.8 正常吗", "在正常范围内……", "那我晚饭呢"),
                texts(ChatMemoryReadAdvisor.merge(history, current)));
    }

    @Test
    void 历史末尾是助手消息时不去重() {
        List<Message> history = List.of(
                new UserMessage("那我晚饭呢"),
                new AssistantMessage("晚饭建议……"));
        List<Message> current = List.of(new UserMessage("晚饭建议……"));

        assertEquals(List.of("那我晚饭呢", "晚饭建议……", "晚饭建议……"),
                texts(ChatMemoryReadAdvisor.merge(history, current)));
    }

    @Test
    void 无历史时只保留本轮消息() {
        List<Message> current = List.of(new UserMessage("你好"));

        assertEquals(List.of("你好"), texts(ChatMemoryReadAdvisor.merge(List.of(), current)));
    }

    // ---------- 辅助 ----------

    /** 走一遍顾问，返回真正被送进链路的 prompt 消息列表。 */
    private static List<Message> injectedMessages(ChatMemoryReadAdvisor advisor, Message currentMessage) {
        List<ChatClientRequest> captured = new ArrayList<>();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(currentMessage)),
                Map.of(ChatMemory.CONVERSATION_ID, String.valueOf(SESSION_ID)));

        advisor.adviseCall(request, captureChain(captured));

        assertTrue(!captured.isEmpty(), "顾问应把请求继续往下传");
        return captured.get(0).prompt().getInstructions();
    }

    /** 一个只负责"记下流过去的请求"的链尾桩：三个抽象方法都要实现，接口不是函数式接口。 */
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
