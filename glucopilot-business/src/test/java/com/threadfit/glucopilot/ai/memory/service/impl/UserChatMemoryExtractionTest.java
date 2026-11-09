package com.threadfit.glucopilot.ai.memory.service.impl;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import com.threadfit.glucopilot.ai.client.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.memory.dto.MemoryExtractionDtos;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryMapper;
import com.threadfit.glucopilot.ai.memory.service.impl.UserChatMemoryServiceImpl;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.ChatRoundGuard;
import com.threadfit.glucopilot.common.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆抽取的两条路径：结构化输出（{@code entity}）与自由文本 + 宽松解析。
 *
 * <p>守三件事：①结构化路径拿到的候选也要过 {@code upsert} 的四道闸（键白名单/置信度/危险词/长度）；
 * ②同一批里重复 key 只留置信度最高的一条（否则"哪条覆盖哪条"取决于模型输出顺序）；
 * ③开关关闭时不能悄悄走 {@code entity}（mock 的客户端只桩了 {@code content()}）。
 */
class UserChatMemoryExtractionTest {

    private AiChatClientRegistry clients;
    private ChatUserMemoryMapper memoryMapper;
    private ChatMessageMapper chatMessageMapper;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private AIProperties aiProperties;
    private UserChatMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        clients = mock(AiChatClientRegistry.class);
        memoryMapper = mock(ChatUserMemoryMapper.class);
        chatMessageMapper = mock(ChatMessageMapper.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        aiProperties = new AIProperties();

        AiPromptTemplateService promptService = mock(AiPromptTemplateService.class);
        SceneModelResolver resolver = mock(SceneModelResolver.class);
        when(promptService.systemPrompt(SceneCode.MEMORY)).thenReturn("系统提示词");
        when(resolver.memoryOptionsBuilder()).thenReturn(mock(ChatOptions.Builder.class));

        when(clients.utilityAvailable()).thenReturn(true);
        when(clients.utilityClient()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        // 一条用户消息，让 buildInput 有内容
        ChatMessage message = new ChatMessage();
        message.setId(1L);
        message.setMsgRole(1);
        message.setContentText("我桥本氏甲减，不吃海带");
        message.setGmtCreate(LocalDateTime.now());
        when(chatMessageMapper.selectRecentBySession(any(), any())).thenReturn(List.of(message));
        when(memoryMapper.selectOne(any())).thenReturn(null);

        service = new UserChatMemoryServiceImpl(clients, memoryMapper, chatMessageMapper, promptService,
                resolver, aiProperties, new JsonUtils(new tools.jackson.databind.json.JsonMapper()), new ChatRoundGuard(),
                mock(Executor.class));
    }

    @Test
    @DisplayName("结构化输出：候选仍要过四道闸，重复 key 只留置信度最高的一条")
    void structuredPayloadIsFilteredAndDeduped() {
        aiProperties.getMemory().setStructuredOutput(true);
        when(callSpec.entity(MemoryExtractionDtos.MemoryPayload.class)).thenReturn(
                new MemoryExtractionDtos.MemoryPayload(List.of(
                        new MemoryExtractionDtos.MemoryItemPayload("diet_taboo", "不吃海带", 90),
                        // 同 key 低置信度：应被高置信度覆盖
                        new MemoryExtractionDtos.MemoryItemPayload("diet_taboo", "不吃海鲜", 70),
                        // 白名单之外的键：丢弃
                        new MemoryExtractionDtos.MemoryItemPayload("favorite_color", "蓝色", 95),
                        // 置信度不足：丢弃
                        new MemoryExtractionDtos.MemoryItemPayload("habit", "爱喝咖啡", 30),
                        // 空内容：丢弃
                        new MemoryExtractionDtos.MemoryItemPayload("preference", "  ", 99))));

        int written = service.runExtract(7L, 8L, "explicit");

        assertThat(written).isEqualTo(1);
        ArgumentCaptor<ChatUserMemoryEntity> captor = ArgumentCaptor.forClass(ChatUserMemoryEntity.class);
        verify(memoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getMemoryKey()).isEqualTo("diet_taboo");
        assertThat(captor.getValue().getMemoryText()).isEqualTo("不吃海带");
        assertThat(captor.getValue().getConfidence()).isEqualTo(90);
    }

    @Test
    @DisplayName("结构化输出返回空列表时一条都不写，且不抛异常")
    void emptyPayloadWritesNothing() {
        aiProperties.getMemory().setStructuredOutput(true);
        when(callSpec.entity(MemoryExtractionDtos.MemoryPayload.class))
                .thenReturn(new MemoryExtractionDtos.MemoryPayload(List.of()));

        assertThat(service.runExtract(7L, 8L, "explicit")).isZero();
        verify(memoryMapper, never()).insert(any(ChatUserMemoryEntity.class));
    }

    @Test
    @DisplayName("开关关闭时走 content()，不得调用 entity()")
    void legacyPathUsesContent() {
        aiProperties.getMemory().setStructuredOutput(false);
        when(callSpec.content()).thenReturn("""
                {"memories":[{"key":"glucose_goal","text":"餐后控制在 8 以下","confidence":88}]}
                """);

        assertThat(service.runExtract(7L, 8L, "explicit")).isEqualTo(1);
        verify(callSpec, never()).entity(any(Class.class));
        ArgumentCaptor<ChatUserMemoryEntity> captor = ArgumentCaptor.forClass(ChatUserMemoryEntity.class);
        verify(memoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getMemoryText()).isEqualTo("餐后控制在 8 以下");
    }
}
