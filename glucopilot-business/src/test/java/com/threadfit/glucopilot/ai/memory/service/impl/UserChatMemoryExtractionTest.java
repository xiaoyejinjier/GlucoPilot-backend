package com.threadfit.glucopilot.ai.memory.service.impl;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
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
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private ChatSessionMapper chatSessionMapper;
    private Executor executor;
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
        chatSessionMapper = mock(ChatSessionMapper.class);
        executor = mock(Executor.class);
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

        service = new UserChatMemoryServiceImpl(clients, memoryMapper, chatMessageMapper, chatSessionMapper, promptService,
                resolver, aiProperties, new JsonUtils(new tools.jackson.databind.json.JsonMapper()), new ChatRoundGuard(),
                executor);
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

    @Test
    @DisplayName("拆窗：新→旧的列表要切成若干「时间正序」的窗口，且老窗排在前面")
    void splitOldestFirstKeepsChronologicalWindows() {
        List<ChatMessage> recentDesc = List.of(message(105L, "第5句"), message(104L, "第4句"),
                message(103L, "第3句"), message(102L, "第2句"), message(101L, "第1句"));

        List<List<ChatMessage>> windows = UserChatMemoryServiceImpl.splitOldestFirst(recentDesc, 2);

        assertThat(windows).hasSize(3);
        assertThat(windows.get(0)).extracting(ChatMessage::getId).containsExactly(101L, 102L);
        assertThat(windows.get(1)).extracting(ChatMessage::getId).containsExactly(103L, 104L);
        assertThat(windows.get(2)).extracting(ChatMessage::getId).containsExactly(105L);
    }

    @Test
    @DisplayName("长会话按窗口抽取：老窗先送模型，新窗后送（后写的新说法才留得下来）")
    void longSessionIsExtractedWindowByWindowOldestFirst() {
        aiProperties.getMemory().setStructuredOutput(false);
        aiProperties.getMemory().setMemoryExtractWindowMessages(1);
        aiProperties.getMemory().setMemoryExtractMaxWindows(2);
        // 入参是新→旧：id 大的是最近说的
        when(chatMessageMapper.selectRecentBySession(any(), any())).thenReturn(
                List.of(message(100L, "我最近不能吃辣了"), message(99L, "医生让我少油少盐")));
        when(callSpec.content()).thenReturn("{\"memories\":[]}");

        service.runExtract(7L, 8L, "session-ended");

        // 回看上限 = 1 条/窗 × 2 窗
        verify(chatMessageMapper).selectRecentBySession(8L, 2);
        ArgumentCaptor<String> inputs = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, org.mockito.Mockito.times(2)).user(inputs.capture());
        assertThat(inputs.getAllValues().get(0)).contains("少油少盐").doesNotContain("不能吃辣");
        assertThat(inputs.getAllValues().get(1)).contains("不能吃辣").doesNotContain("少油少盐");
        // 上界 = 这次真正取到的第一条（最近一条），只用于归档补齐判断，不代表「抽全了」
        verify(chatSessionMapper).markMemoryExtracted(8L, 100L);
    }

    @Test
    @DisplayName("进度标记写失败不影响抽取结果：记忆已经写进去了就不该因为标记失败回滚")
    void failedProgressMarkDoesNotBreakExtraction() {
        aiProperties.getMemory().setStructuredOutput(false);
        when(callSpec.content()).thenReturn("{\"memories\":[]}");
        when(chatSessionMapper.markMemoryExtracted(any(), any())).thenThrow(new RuntimeException("db down"));

        int written = service.runExtract(7L, 8L, "session-ended");

        assertThat(written).isZero();
        verify(chatSessionMapper).markMemoryExtracted(8L, 1L);
    }

    @Test
    @DisplayName("关键词兜底默认关闭：没有 tool calling 的部署不打开它，就不该因为「记住」两个字烧模型")
    void keywordFallbackIsOffByDefault() {
        service.extractAsync(7L, 8L, "explicit");

        verify(executor, org.mockito.Mockito.never()).execute(any());
        verify(chatMessageMapper, org.mockito.Mockito.never()).selectRecentBySession(any(), anyInt());
    }

    @Test
    @DisplayName("关键词兜底打开后只回看最近 6 条：它由聊天主链路顺带触发，不能像终局复盘那样回看 80 条")
    void keywordFallbackUsesNarrowWindow() {
        aiProperties.getMemory().setKeywordTriggerFallback(true);
        aiProperties.getMemory().setStructuredOutput(false);
        when(chatMessageMapper.selectRecentBySession(any(), any())).thenReturn(List.of(message(100L, "记住我不吃辣")));
        when(callSpec.content()).thenReturn("{\"memories\":[]}");

        service.runExtract(7L, 8L, "explicit");

        verify(chatMessageMapper).selectRecentBySession(8L, 6);
        verify(requestSpec, org.mockito.Mockito.times(1)).user(anyString());
    }

    /**
     * 防复活：用户删掉某个键之后，<b>自动抽取</b>在冷却期内不许把它重新插回来。
     *
     * <p>这条闸门存在的理由：删除是逻辑删除，而终局抽取读的是会话原文——同一段对话还在，
     * 那个事实就会被重新读出来、再 insert 一条生效行（唯一键带 {@code isDeleted}，不撞键），
     * 用户看到的就是"删了又回来"。
     *
     * <p>同时守住边界：用户当轮亲口说的（EXPLICIT）不受冷却限制，他真改主意时不该被挡住。
     */
    @Test
    @DisplayName("删过的键在冷却期内不被自动抽取重建，但用户当轮亲口说的照记")
    void cooldownBlocksBatchButNotExplicitWrite() {
        when(memoryMapper.selectLastDeletedAt(7L, "habit")).thenReturn(LocalDateTime.now().minusDays(3));

        // 自动抽取（BATCH）：被第五道闸拦下，连 insert 都不发
        assertThat(service.upsert(7L, "habit", "每天晚饭后散步30分钟", 90, null)).isFalse();
        verify(memoryMapper, never()).insert(any(ChatUserMemoryEntity.class));

        // 用户当轮亲口说（EXPLICIT）：不挡
        assertThat(service.saveExplicit(7L, null, "habit", "每天早饭后散步20分钟").saved()).isTrue();
        verify(memoryMapper).insert(any(ChatUserMemoryEntity.class));
    }

    /** 冷却天数配成 0 等于关掉这道闸门：连"上次删除时间"都不查。 */
    @Test
    @DisplayName("冷却天数设为 0 就关掉这道闸门")
    void cooldownCanBeDisabled() {
        aiProperties.getMemory().setDeletedKeyCooldownDays(0);

        assertThat(service.upsert(7L, "habit", "每天晚饭后散步30分钟", 90, null)).isTrue();

        verify(memoryMapper, never()).selectLastDeletedAt(any(), any());
        verify(memoryMapper).insert(any(ChatUserMemoryEntity.class));
    }

    private static ChatMessage message(Long id, String text) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setMsgRole(1);
        message.setContentText(text);
        message.setGmtCreate(LocalDateTime.now());
        return message;
    }
}
