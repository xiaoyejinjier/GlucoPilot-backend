package com.threadfit.glucopilot.ai.advisor;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatContextShard;
import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import com.threadfit.glucopilot.ai.memory.assembly.ChatHistoryAssembler;
import com.threadfit.glucopilot.ai.memory.reader.ChatContextReader;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.ai.support.TokenEstimator;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两条 Advisor 串成一条链之后的<b>消息顺序</b>回归。
 *
 * <h2>为什么必须有这个测试</h2>
 * 顺序由两个类分别负责一半：{@link DynamicPromptAdvisor} 把场景提示词放进 {@code current} 的最前面，
 * {@link com.threadfit.glucopilot.ai.memory.assembly.ChatHistoryAssembler} 负责在拼历史时把它
 * 从"本轮新增消息"里拆出来、仍旧放在最前。
 * 两边的单元测试各自都绿——因为各自只检查自己那一半：
 * <ul>
 *   <li>{@code DynamicPromptAdvisorTest} 只断言"替换之后第一条是自己"；</li>
 *   <li>{@code ChatMemoryReadAdvisorTest} 只断言"附加上下文排在历史前面"。</li>
 * </ul>
 * 曾经的真实缺陷就是这样漏过去的：装配器把整个 {@code current} 接在历史后面，
 * 于是第二轮起提示词落到历史与用户提问之间。首轮没有历史，所以看起来一切正常。
 */
class AdvisorChainOrderTest {

    private static final long SESSION_ID = 139L;

    private static final String PROMPT = "你是控糖助手，先看数据再给建议；涉及用药必须建议就医。";

    private final AIProperties properties = new AIProperties();

    private final ChatHistoryAssembler assembler = new ChatHistoryAssembler(properties, new TokenEstimator());

    @Test
    void 两条顾问串起来后系统提示词仍在历史之前() {
        AiPromptTemplateService prompts = mock(AiPromptTemplateService.class);
        when(prompts.systemPrompt(SceneCode.CHAT)).thenReturn(PROMPT);

        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(String.valueOf(SESSION_ID))).thenReturn(
                List.of(new UserMessage("我早餐吃了两个包子"), new AssistantMessage("记下了，碳水和油脂都偏高")));

        ChatContextReader contextReader = mock(ChatContextReader.class);
        when(contextReader.summary(SESSION_ID)).thenReturn("用户早餐常吃包子，目标餐后 8 以内");
        when(contextReader.recentToolDigests(SESSION_ID)).thenReturn(List.of());
        when(contextReader.longTermMemory(any())).thenReturn(List.of());

        List<ChatClientRequest> captured = new ArrayList<>();
        AtomicReference<List<ChatContextShard>> shardSlot = new AtomicReference<>(List.of());
        CallAdvisorChain chain = chainOf(List.of(
                new DynamicPromptAdvisor(prompts, SceneCode.CHAT),
                new ChatMemoryReadAdvisor(chatMemory, assembler, contextReader)), captured);

        chain.nextCall(new ChatClientRequest(
                new Prompt(List.of(new UserMessage("那晚饭吃什么"))),
                Map.of(ChatMemory.CONVERSATION_ID, String.valueOf(SESSION_ID),
                        ChatMemoryAdvisorParams.USER_ID, 7L,
                        ChatMemoryAdvisorParams.FIXED_OVERHEAD_TOKENS, 1600,
                        ChatMemoryAdvisorParams.CONTEXT_SHARDS, shardSlot)));

        assertEquals(1, captured.size(), "链必须把请求一路传到终点");
        List<Message> instructions = captured.get(0).prompt().getInstructions();

        assertEquals(5, instructions.size(), "提示词 + 附加上下文 + 2 条历史 + 1 条本轮");
        assertInstanceOf(SystemMessage.class, instructions.get(0));
        assertEquals(PROMPT, instructions.get(0).getText(),
                "第二轮起提示词必须仍是第一条：否则人设与医疗约束会被历史冲淡");
        assertInstanceOf(SystemMessage.class, instructions.get(1));
        assertTrue(instructions.get(1).getText().contains("用户早餐常吃包子"),
                "记忆/摘要作为第二条系统消息接在提示词之后");
        assertEquals("我早餐吃了两个包子", instructions.get(2).getText());
        assertEquals("那晚饭吃什么", instructions.get(4).getText());

        List<String> keys = shardSlot.get().stream().map(ChatContextShard::contextKey).toList();
        assertEquals(List.of(ChatContextShard.KEY_SYSTEM_PROMPT,
                ChatContextShard.KEY_CONTEXT_SUMMARY,
                ChatContextShard.KEY_HISTORY_MESSAGES), keys,
                "分片账本顺序必须与真实消息顺序一致");
    }

    /**
     * 一条把"当前顾问 → 下一条顾问 → … → 终点"串起来的最小链。
     * 终点把收到的请求收进 {@code captured}，这样断言看到的就是真正会发给模型的那份 prompt。
     */
    private static CallAdvisorChain chainOf(List<? extends CallAdvisor> advisors, List<ChatClientRequest> captured) {
        return new CallAdvisorChain() {
            private int index;

            @Override
            public ChatClientResponse nextCall(ChatClientRequest request) {
                if (index >= advisors.size()) {
                    captured.add(request);
                    return ChatClientResponse.builder().build();
                }
                return advisors.get(index++).adviseCall(request, this);
            }

            @Override
            public List<CallAdvisor> getCallAdvisors() {
                return List.copyOf(advisors);
            }

            @Override
            public CallAdvisorChain copy(CallAdvisor advisor) {
                return this;
            }
        };
    }
}
