package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.model.capability.AiModelCapability;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.ai.support.AiCallContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 唯一漏斗的记账：同步成功/失败各一行、流式只记一行、实现缺失时不影响调用。
 *
 * <h2>为什么"流式只记一行"要单独测</h2>
 * 流式的收尾有两个钩子（{@code doOnError} / {@code doFinally}），失败时两者都会触发。
 * 如果各写一次，报表里的"调用次数"会在所有失败场景下翻倍——而这个错误只会在
 * <b>用户已经遇到报错的请求</b>上出现，最容易在生产里被忽略。
 */
class DynamicChatModelCallLogTest {

    private final AiCallRecorder recorder = mock(AiCallRecorder.class);
    private final AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
    private final ChatModelFactory factory = mock(ChatModelFactory.class);

    private static AiModelSpec yamlTextSpec() {
        return new AiModelSpec("deepseek-v4-pro", "DeepSeek 文本模型（yaml）", 1,
                "https://api.deepseek.com", "sk-yaml", "deepseek-v4-pro",
                0.7, null, 2048, null, Duration.ofSeconds(60), "yaml", AiModelCapability.text());
    }

    private static AiRuntimeConfigService.Snapshot snapshot() {
        AiModelSpec yamlText = yamlTextSpec();
        AiModelSpec yamlVision = new AiModelSpec("qwen3-vl-plus", "视觉模型（yaml）", 2,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-vision", "qwen3-vl-plus",
                0.2, null, 512, null, Duration.ofSeconds(60), "yaml", AiModelCapability.visionDefaults());
        return new AiRuntimeConfigService.Snapshot(1L, new HashMap<>(), new HashMap<>(),
                yamlText, yamlVision, yamlText, "yaml 兜底", null, "yaml 兜底", 0);
    }

    @SuppressWarnings("unchecked")
    private DynamicChatModel model(ChatModel delegate) {
        when(runtime.snapshot()).thenReturn(snapshot());
        ObjectProvider<AiCallRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        return new DynamicChatModel("text", runtime, factory,
                AiRuntimeConfigService.Snapshot::resolveTextDefault,
                AiRuntimeConfigService.Snapshot::yamlText,
                () -> delegate, provider);
    }

    /**
     * 造一个真实响应而不是 mock：{@code ChatResponse.getMetadata()} 是 final 方法，
     * Mockito 在没有 inline mock maker 的默认配置下桩不了它（会报 UnfinishedStubbing，
     * 而错误信息指向的是"少写 thenReturn"，很容易带偏排查方向）。
     */
    private static ChatResponse response(Usage usage) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("好的"))))
                .metadata(ChatResponseMetadata.builder().usage(usage).build())
                .build();
    }

    private static Usage usage(int prompt, int completion, Long cacheRead) {
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return prompt;
            }

            @Override
            public Integer getCompletionTokens() {
                return completion;
            }

            @Override
            public Long getCacheReadInputTokens() {
                return cacheRead;
            }

            @Override
            public Object getNativeUsage() {
                // DeepSeek 走 OpenAI 兼容通道时由框架映射进 getCacheReadInputTokens()，
                // 这里不需要再模拟 native 字段（那条路径由 ModelCallUsageTest 覆盖）
                return null;
            }
        };
    }

    @Test
    void 同步成功应记一行带用量与归属() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse first = response(usage(1000, 200, 800L));
        when(delegate.call(any(Prompt.class))).thenReturn(first);
        DynamicChatModel model = model(delegate);
        AiCallContext.Call call = new AiCallContext.Call(SceneCode.CHAT, 27L, 178L);

        ChatResponse result = AiCallContext.run(call, () -> model.call(new Prompt("你好")));

        assertThat(result).isNotNull();
        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(recorder, times(1)).record(captor.capture());
        AiCallRecord row = captor.getValue();
        assertThat(row.context().scene()).isEqualTo(SceneCode.CHAT);
        assertThat(row.context().userId()).isEqualTo(27L);
        assertThat(row.context().sessionId()).isEqualTo(178L);
        assertThat(row.channel()).isEqualTo("text");
        assertThat(row.modelCode()).isEqualTo("deepseek-v4-pro");
        assertThat(row.usage().promptTokens()).isEqualTo(1000);
        assertThat(row.usage().cacheHitTokens()).isEqualTo(800L);
        assertThat(row.usage().cacheMissTokens()).isEqualTo(200L);
        assertThat(row.stream()).isFalse();
        assertThat(row.ok()).isTrue();
        assertThat(row.errorCode()).isNull();
    }

    @Test
    void 同步失败也要记一行并把异常原样抛出() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenThrow(new IllegalStateException("厂商 400"));
        DynamicChatModel model = model(delegate);

        assertThatThrownBy(() -> AiCallContext.run(new AiCallContext.Call(SceneCode.CHAT, 1L, 2L),
                () -> model.call(new Prompt("你好"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("厂商 400");

        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(recorder, times(1)).record(captor.capture());
        assertThat(captor.getValue().ok()).isFalse();
        assertThat(captor.getValue().errorCode()).isEqualTo("IllegalStateException");
        assertThat(captor.getValue().usage()).isNull();
    }

    @Test
    void 流式失败时也只能记一行() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse chunk = response(usage(500, 10, 300L));
        when(delegate.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(chunk),
                        Flux.error(new IllegalStateException("流中断了"))));
        DynamicChatModel model = model(delegate);

        assertThatThrownBy(() -> AiCallContext.run(new AiCallContext.Call(SceneCode.CHAT, 1L, 2L),
                () -> model.stream(new Prompt("你好")).collectList().block()))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(recorder, times(1)).record(captor.capture());
        AiCallRecord row = captor.getValue();
        assertThat(row.stream()).isTrue();
        assertThat(row.ok()).isFalse();
        assertThat(row.usage().promptTokens()).isEqualTo(500);
    }

    @Test
    void 流式成功应记一行并带聚合后的用量() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse chunk = response(usage(800, 60, 640L));
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(chunk));
        DynamicChatModel model = model(delegate);

        // 流式的归属走 Reactor Context（不是 ThreadLocal）：见 AiCallContext#fromContext
        AiCallContext.Call call = new AiCallContext.Call(SceneCode.CHAT, 3L, 4L);
        model.stream(new Prompt("你好")).collectList()
                .contextWrite(context -> context.put(AiCallContext.CONTEXT_KEY, call))
                .block();

        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(recorder, times(1)).record(captor.capture());
        assertThat(captor.getValue().ok()).isTrue();
        assertThat(captor.getValue().usage().cacheHitTokens()).isEqualTo(640L);
        assertThat(captor.getValue().context().sessionId()).isEqualTo(4L);
    }

    @Test
    void 没有账单实现时调用应照常进行() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse reply = response(usage(10, 2, null));
        when(delegate.call(any(Prompt.class))).thenReturn(reply);
        when(runtime.snapshot()).thenReturn(snapshot());
        DynamicChatModel model = new DynamicChatModel("text", runtime, factory,
                AiRuntimeConfigService.Snapshot::resolveTextDefault,
                AiRuntimeConfigService.Snapshot::yamlText,
                () -> delegate, null);

        assertThat(model.call(new Prompt("你好"))).isNotNull();
        verify(recorder, never()).record(any());
    }

    /** 只是为了写清"未压缩的历史里必须有 Generation 才有正文"，这里不参与断言。 */
    @SuppressWarnings("unused")
    private static ChatResponse realResponse() {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("hi"))))
                .build();
    }
}
