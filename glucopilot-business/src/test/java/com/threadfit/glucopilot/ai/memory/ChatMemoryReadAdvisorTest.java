package com.threadfit.glucopilot.ai.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 记忆注入的合并/去重逻辑。
 * <p>
 * 这一段的坑在于：调用补全接口之前，本轮用户消息<b>已经落库</b>（2.24 先写消息，2.25 再开流），
 * 所以历史末尾会带上它；如果请求里也带了同一条，直接拼接就会把同一句话送两遍。
 */
class ChatMemoryReadAdvisorTest {

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).collect(Collectors.toList());
    }

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
}
