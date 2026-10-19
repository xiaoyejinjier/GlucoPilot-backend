package com.threadfit.glucopilot.ai.prompt.advisor;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.threadfit.glucopilot.ai.prompt.advisor.DynamicPromptAdvisor;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.prompt.service.impl.AiPromptTemplateServiceImpl;

/**
 * 每请求注入场景提示词。
 * <p>
 * 守住的语义（按重要性）：
 * <ol>
 *   <li><b>提示词永远在消息列表第一条</b>，且历史/本轮消息一条不少；</li>
 *   <li><b>替换而不是追加</b>——请求里若带着一份（旧）系统提示词，必须被丢掉。
 *       两段互相矛盾的指令同时进模型是最难排查的一类问题；</li>
 *   <li>内容没变时不重建消息列表（避免每个 token 白抄一遍）。</li>
 * </ol>
 */
class DynamicPromptAdvisorTest {

    private static AiPromptTemplateService service() {
        AIProperties properties = new AIProperties();
        for (SceneCode scene : SceneCode.values()) {
            AIProperties.SceneProperties config = new AIProperties.SceneProperties();
            config.setPrompt("prompts/chat/system.md");
            properties.getScenes().put(scene.getConfigKey(), config);
        }
        AiPromptTemplateServiceImpl service = new AiPromptTemplateServiceImpl(properties, null);
        service.init();
        return service;
    }

    @Test
    void 无系统消息时注入到最前面且保留原有消息顺序() {
        DynamicPromptAdvisor advisor = new DynamicPromptAdvisor(service(), SceneCode.CHAT);
        List<Message> injected = through(advisor, List.of(
                new UserMessage("早餐前血糖 6.8 正常吗"),
                new AssistantMessage("在正常范围内……"),
                new UserMessage("那晚饭呢")));

        assertEquals(4, injected.size());
        assertTrue(injected.get(0) instanceof SystemMessage);
        assertEquals("早餐前血糖 6.8 正常吗", text(injected.get(1)));
        assertEquals("在正常范围内……", text(injected.get(2)));
        assertEquals("那晚饭呢", text(injected.get(3)));
    }

    @Test
    void 请求里带着旧系统提示词时被替换而不是叠加() {
        DynamicPromptAdvisor advisor = new DynamicPromptAdvisor(service(), SceneCode.CHAT);
        List<Message> injected = through(advisor, List.of(
                new SystemMessage("这是启动期烤死的旧提示词"),
                new UserMessage("你好")));

        assertEquals(2, injected.size(), "旧系统消息必须被丢掉，不能出现两条 system");
        assertTrue(injected.get(0) instanceof SystemMessage);
        assertFalse(text(injected.get(0)).contains("启动期烤死"));
        assertTrue(text(injected.get(0)).contains("不能替代线下医院诊疗"));
        assertEquals("你好", text(injected.get(1)));
    }

    @Test
    void 内容与请求里的系统消息完全相同时原样放行() {
        AiPromptTemplateService service = service();
        DynamicPromptAdvisor advisor = new DynamicPromptAdvisor(service, SceneCode.CHAT);
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new SystemMessage(service.systemPrompt(SceneCode.CHAT)),
                        new UserMessage("你好"))),
                Map.of());

        assertSame(request, advisor.withPrompt(request), "无需重建时应返回同一个请求对象");
    }

    @Test
    void 系统消息有多条时按替换处理() {
        DynamicPromptAdvisor advisor = new DynamicPromptAdvisor(service(), SceneCode.CHAT);
        List<Message> injected = through(advisor, List.of(
                new SystemMessage("旧一"),
                new SystemMessage("旧二"),
                new UserMessage("你好")));

        assertEquals(2, injected.size());
        assertTrue(text(injected.get(0)).contains("不能替代线下医院诊疗"));
        assertEquals("你好", text(injected.get(1)));
    }

    @Test
    void 注入的内容恒等于当前生效提示词() {
        AiPromptTemplateService service = service();
        DynamicPromptAdvisor advisor = new DynamicPromptAdvisor(service, SceneCode.CHAT);
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new SystemMessage("旧提示词"), new UserMessage("你好"))),
                Map.of());

        ChatClientRequest rebuilt = advisor.withPrompt(request);
        assertNotSame(request, rebuilt, "请求里有旧系统提示词时必须重建");

        // 「后台改完不用重启」的落点：注入内容来自服务每次读取的当前提示词，
        // 而不是构造 client 时烤死的那一份
        assertEquals(service.systemPrompt(SceneCode.CHAT),
                text(rebuilt.prompt().getInstructions().get(0)));
    }

    // ---------- 辅助 ----------

    private static List<Message> through(DynamicPromptAdvisor advisor, List<Message> messages) {
        List<ChatClientRequest> captured = new ArrayList<>();
        advisor.adviseCall(new ChatClientRequest(new Prompt(messages), Map.of()), captureChain(captured));
        assertFalse(captured.isEmpty(), "顾问应把请求继续往下传");
        return captured.get(0).prompt().getInstructions();
    }

    private static String text(Message message) {
        return message.getText();
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
}
