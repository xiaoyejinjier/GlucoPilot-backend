package com.threadfit.glucopilot.ai.client;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;

import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import com.threadfit.glucopilot.ai.config.AiChatClientConfig;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryMapper;
import com.threadfit.glucopilot.ai.memory.service.impl.ChatSummaryServiceImpl;
import com.threadfit.glucopilot.ai.memory.service.impl.UserChatMemoryServiceImpl;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.lock.AiConcurrencyPolicy;
import com.threadfit.glucopilot.common.api.PageQuery;
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
        AiConcurrencyPolicy guard = mock(AiConcurrencyPolicy.class);
        ChatUserMemoryMapper mapper = mock(ChatUserMemoryMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        // listOwnForUser 走 selectPage：不给桩会拿到 null，getRecords() 直接 NPE
        when(mapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));
        when(mapper.logicDeleteAllByUser(42L, null)).thenReturn(2);
        // 清空入口会先数一遍生效行（0 行就直接早退，不去动墓碑），所以这里必须给数
        when(mapper.countActiveByUser(42L)).thenReturn(2);
        new ApplicationContextRunner()
            .withPropertyValues("spring.ai.model.chat=none")
            .withBean(AIProperties.class, AIProperties::new)
            .withBean(ChatSessionMapper.class, () -> mock(ChatSessionMapper.class))
            .withBean(ChatMessageMapper.class, () -> mock(ChatMessageMapper.class))
            .withBean(ChatUserMemoryMapper.class, () -> mapper)
            .withBean(AiPromptTemplateService.class, () -> mock(AiPromptTemplateService.class))
            .withBean(SceneModelResolver.class, () -> mock(SceneModelResolver.class))
            .withBean(AiChatClientRegistry.class, () -> {
                // 无密钥部署：registry 拿不到 summary client，摘要/记忆抽取按"可选"静默跳过
                AiChatClientRegistry registry = mock(AiChatClientRegistry.class);
                when(registry.utilityAvailable()).thenReturn(false);
                return registry;
            })
            .withBean(JsonUtils.class, () -> mock(JsonUtils.class))
            .withBean(AiConcurrencyPolicy.class, () -> guard)
            .withBean("chatSummaryExecutor", Executor.class, () -> executor)
            // 任务队列的派发口：默认 mock 返回 false ⇒ 退回本地线程池（本用例正是要断言这一点）
            .withBean(com.threadfit.glucopilot.ai.memory.mq.MemoryExtractJobs.class,
                    () -> mock(com.threadfit.glucopilot.ai.memory.mq.MemoryExtractJobs.class))
            .withUserConfiguration(AiChatClientConfig.class, ChatSummaryServiceImpl.class,
                    UserChatMemoryServiceImpl.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ChatClient.class);
                context.getBean(ChatSummaryServiceImpl.class).maybeSummarizeAsync(42L, 7L);
                UserChatMemoryServiceImpl memory = context.getBean(UserChatMemoryServiceImpl.class);
                memory.extractAsync(42L, 7L, "session-end");
                verifyNoInteractions(executor, guard);
                assertThat(memory.listOwnForUser(42L, null, new PageQuery()).getRecords()).isEmpty();
                assertThat(memory.deleteAllForUser(42L)).isEqualTo(2);
                verify(mapper).logicDeleteAllByUser(42L, null);
            });
    }
}
