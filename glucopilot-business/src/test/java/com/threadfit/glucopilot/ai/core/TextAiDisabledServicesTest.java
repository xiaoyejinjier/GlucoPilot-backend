package com.threadfit.glucopilot.ai.core;

import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiChatClientConfig;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryMapper;
import com.threadfit.glucopilot.ai.memory.service.impl.ChatSummaryServiceImpl;
import com.threadfit.glucopilot.ai.memory.service.impl.UserChatMemoryServiceImpl;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.ChatRoundGuard;
import com.threadfit.glucopilot.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import java.util.concurrent.Executor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TextAiDisabledServicesTest {
    @Test
    void memoryServicesStartWithoutModelAndKeepSelfServiceAvailable() {
        Executor executor = mock(Executor.class);
        ChatRoundGuard guard = mock(ChatRoundGuard.class);
        ChatUserMemoryMapper mapper = mock(ChatUserMemoryMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.logicDeleteAllByUser(42L, null)).thenReturn(2);
        new ApplicationContextRunner()
            .withPropertyValues("spring.ai.model.chat=none")
            .withBean(AIProperties.class, AIProperties::new)
            .withBean(ChatSessionMapper.class, () -> mock(ChatSessionMapper.class))
            .withBean(ChatMessageMapper.class, () -> mock(ChatMessageMapper.class))
            .withBean(ChatUserMemoryMapper.class, () -> mapper)
            .withBean(AiPromptTemplateService.class, () -> mock(AiPromptTemplateService.class))
            .withBean(SceneModelResolver.class, () -> mock(SceneModelResolver.class))
            .withBean(JsonUtils.class, () -> mock(JsonUtils.class))
            .withBean(ChatRoundGuard.class, () -> guard)
            .withBean("chatSummaryExecutor", Executor.class, () -> executor)
            .withUserConfiguration(AiChatClientConfig.class, ChatSummaryServiceImpl.class,
                    UserChatMemoryServiceImpl.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ChatClient.class);
                context.getBean(ChatSummaryServiceImpl.class).maybeSummarizeAsync(42L, 7L);
                UserChatMemoryServiceImpl memory = context.getBean(UserChatMemoryServiceImpl.class);
                memory.extractAsync(42L, 7L, "session-end");
                verifyNoInteractions(executor, guard);
                assertThat(memory.listOwnForUser(42L)).isEmpty();
                assertThat(memory.deleteAllForUser(42L)).isEqualTo(2);
                verify(mapper).logicDeleteAllByUser(42L, null);
            });
    }
}