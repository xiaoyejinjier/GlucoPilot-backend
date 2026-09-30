package com.threadfit.glucopilot.ai.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 用户消息的文本<b>不参与模板渲染</b>——拼接用户内容时不必转义花括号。
 * <p>
 * 背景：{@code AiPromptTemplateService} 用的是 {@code StTemplateRenderer} +
 * {@code ValidationMode.THROW}（缺变量立刻报错），并且被设成了 ChatClient 的
 * {@code defaultTemplateRenderer}。于是必须回答一个边界问题：
 * <b>用户自己打的文本</b>、以及<b>视觉模型产出的图片描述</b>里出现花括号时，
 * 会不会被当成「未声明的模板变量」而抛异常？
 * <p>
 * 结论（本用例锁定）：<b>不会</b>。系统提示词走模板渲染，而 {@code .user(...)} 的文本原样透传。
 * 这直接决定了阶段 2 的拼装方式：可以直接把图片描述拼进用户消息，
 * 不需要转义、也不需要绕 {@code param()} 传值。
 * <p>
 * <b>踩坑记录</b>：写这组用例时最初漏了 {@code .content()}，
 * 结果 {@code CallResponseSpec.call()} 只是返回一个规格对象、<b>并不触发模型调用</b>，
 * 断言因此拿到 null。真正发起调用（也就是真正渲染）的是 {@code content()}。
 * 这一点在写任何基于假 ChatModel 的用例时都要注意。
 */
class UserTextTemplateRenderingTest {

    /** 记录送进模型的 Prompt，用于断言实际透传的文本。 */
    private static final class CapturingChatModel implements ChatModel {

        private Prompt captured;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.captured = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.captured = prompt;
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        }
    }

    private static ChatClient client(CapturingChatModel model) {
        return ChatClient.builder(model)
                .defaultTemplateRenderer(StTemplateRenderer.builder()
                        .validationMode(ValidationMode.THROW)
                        .build())
                .build();
    }

    @Test
    void 用户输入里带花括号不应报错且应原样送入模型() {
        CapturingChatModel model = new CapturingChatModel();

        assertThatCode(() -> client(model).prompt()
                .user("我的血糖是 6.8{这是我随手打的}")
                .call()
                .content())
                .as("THROW 校验下，用户输入的花括号不应该把请求打成 500")
                .doesNotThrowAnyException();

        assertThat(model.captured.getContents()).isEqualTo("我的血糖是 6.8{这是我随手打的}");
    }

    @Test
    void 拼接进用户消息的图片描述中的花括号应原样保留() {
        CapturingChatModel model = new CapturingChatModel();
        // 视觉模型的产出不可控，出现花括号完全可能（比如它顺手写了个 JSON 片段）
        String caption = "一碗牛肉面{疑似含糖}，血糖仪读数 6.8";

        client(model).prompt()
                .user("用户发来了一张图片。图片识别结果如下：\n" + caption)
                .call()
                .content();

        assertThat(model.captured.getContents())
                .contains("图片识别结果如下：")
                .contains(caption);
    }
}
