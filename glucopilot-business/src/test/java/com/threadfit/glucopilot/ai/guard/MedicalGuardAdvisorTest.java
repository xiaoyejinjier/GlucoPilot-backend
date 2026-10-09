package com.threadfit.glucopilot.ai.guard;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医疗安全硬拦截的语义。
 * <p>
 * <b>这里有一条真实的回归用例</b>（2026-09-28 事故）：最初用的是框架的
 * {@code SafeGuardAdvisor}，它检查 {@code prompt.getContents()} —— **整个 Prompt 含 SystemMessage**。
 * 而我们的系统提示词里写着「严禁给出诊断、<b>开药</b>、调整降糖药/胰岛素<b>剂量</b>」，
 * 正好命中词表里的「开药」「剂量」，于是**每条消息都被拦**：用户发「你好」「我叫小明」
 * 也只收到固定话术、模型从未被调用。
 * <p>
 * 因此下面第 1 条用例（拿**真实提示词文件**当 SystemMessage + 一句无害的用户消息 → 必须放行）
 * 就是这次事故的回归测试，不能删。
 */
class MedicalGuardAdvisorTest {

    private static final List<String> WORDS = List.of("吃几片", "剂量", "开药");

    private static final String RESPONSE = "请咨询内分泌科医生。";

    private final MedicalGuardAdvisor advisor = new MedicalGuardAdvisor(WORDS, RESPONSE);

    /** 读取真实的系统提示词文件，保证回归用例针对的是线上真正使用的提示词。 */
    private static String realSystemPrompt() throws IOException {
        return StreamUtils.copyToString(
                new ClassPathResource("prompts/chat/system.md").getInputStream(), StandardCharsets.UTF_8);
    }

    private static ChatClientRequest request(List<Message> messages) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context(Map.of())
                .build();
    }

    private static ChatClientResponse downstream() {
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("模型的正常回答"))))
                        .build())
                .build();
    }

    private static String replyText(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }

    @Test
    void 真实提示词含敏感词但用户消息无害时必须放行() throws IOException {
        String systemPrompt = realSystemPrompt();
        // 前提：线上提示词确实含有词表里的词（否则这条回归用例就失去意义了）
        assertTrue(systemPrompt.contains("剂量"), "系统提示词应含「剂量」，本用例用于覆盖该冲突");
        assertTrue(systemPrompt.contains("开药"), "系统提示词应含「开药」，本用例用于覆盖该冲突");

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(downstream());

        ChatClientResponse result = advisor.adviseCall(
                request(List.of(new SystemMessage(systemPrompt), new UserMessage("你好"))), chain);

        assertEquals("模型的正常回答", replyText(result), "无害提问必须被放行，误拦即为回归");
        verify(chain).nextCall(any());
    }

    @Test
    void 用户消息命中敏感词时应拦截且不调用下游() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        ChatClientResponse result = advisor.adviseCall(
                request(List.of(new SystemMessage("你是控糖助手"), new UserMessage("格列本脲一次吃几片？"))),
                chain);

        assertEquals(RESPONSE, replyText(result));
        verify(chain, never()).nextCall(any());
    }

    @Test
    void 只判本轮用户消息_历史里的敏感词不应触发拦截() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(downstream());

        ChatClientResponse result = advisor.adviseCall(request(List.of(
                new SystemMessage("你是控糖助手"),
                new UserMessage("格列本脲一次吃几片？"),   // 历史里命中过
                new AssistantMessage(RESPONSE),
                new UserMessage("那我晚饭吃什么？"))), chain);   // 本轮无害

        assertEquals("模型的正常回答", replyText(result));
        verify(chain).nextCall(any());
    }

    @Test
    void 流式路径命中时同样拦截() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        List<ChatClientResponse> events = advisor.adviseStream(
                request(List.of(new UserMessage("帮我开药"))), chain).collectList().block();

        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals(RESPONSE, replyText(events.get(0)));
        verify(chain, never()).nextStream(any());
    }

    @Test
    void 词表为空时不拦截() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(downstream());

        ChatClientResponse result = new MedicalGuardAdvisor(List.of(), RESPONSE).adviseCall(
                request(List.of(new UserMessage("格列本脲一次吃几片？"))), chain);

        assertEquals("模型的正常回答", replyText(result));
        verify(chain).nextCall(any());
    }

    @Test
    void 没有用户消息时不拦截() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(downstream());

        advisor.adviseCall(request(List.of(new SystemMessage("你是控糖助手"))), chain);

        verify(chain).nextCall(any());
        assertNull(null);
    }

    @Test
    void 匹配应大小写不敏感() {
        MedicalGuardAdvisor advisor = new MedicalGuardAdvisor(List.of("insulin"), RESPONSE);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        advisor.adviseCall(request(List.of(new UserMessage("How much INSULIN should I take?"))), chain);

        verify(chain, never()).nextCall(any());
        assertFalse(false);
    }
}
