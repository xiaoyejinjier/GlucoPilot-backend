package com.threadfit.glucopilot.ai.core;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import com.threadfit.glucopilot.ai.config.AiChatClientConfig;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 模型客户端路由的装配测试（不启动完整应用，因此不需要 MySQL/Redis）。
 * <p>
 * 盯住两件事：
 * <ol>
 *   <li><b>框架陷阱</b>：{@code ChatClientAutoConfiguration} 是按<b>单个</b> {@code ChatModel} 注入的，
 *       而本模块有两个 {@code ChatClient} Bean（{@code textChatClient} / {@code visionChatClient}）。
 *       构造参数漏了 {@code @Qualifier}，失败会发生在<b>启动期</b>（候选不唯一）——
 *       那种错误只有真实启动才发现得了，所以第一个用例带上视觉 Bean 就是为了在 {@code hasNotFailed()} 处炸出来；</li>
 *   <li><b>语义边界</b>：{@code textClient()} 与 {@code visionClient(scene)} 各自只服务于一种能力。
 *       最初的 {@code client(SceneCode)} 把两者混在一起（传 CHAT_IMAGE 得到视觉 client，
 *       但该场景最终对用户说的话其实出自文本 client），这类歧义会在读代码时被误判，故已拆开。</li>
 * </ol>
 */
class AiChatClientRegistryTest {

    private final ChatClient textClient = mock(ChatClient.class);
    private final ChatClient visionClient = mock(ChatClient.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean("textChatClient", ChatClient.class, () -> textClient)
                .withBean(SceneModelResolver.class, () -> mock(SceneModelResolver.class))
                .withUserConfiguration(AiChatClientRegistry.class);
    }

    @Test
    void disabledTextAiStartsWithoutApiKeyAndRejectsCalls() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DeepSeekChatAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withPropertyValues("spring.ai.model.chat=none", "spring.ai.deepseek.api-key=")
                .withBean(SceneModelResolver.class, () -> mock(SceneModelResolver.class))
                .withUserConfiguration(AiChatClientRegistry.class, AiChatClientConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ChatModel.class);
                    assertThat(context).doesNotHaveBean("textChatClient");
                    BizException ex = assertThrows(BizException.class,
                            () -> context.getBean(AiChatClientRegistry.class).textClient());
                    assertEquals(ErrorCode.BIZ_REJECTED, ex.getErrorCode());
                    assertThat(ex.getMessage()).contains("文本 AI 未启用");
                });
    }

    @Test
    void 未接入视觉provider时上下文应能加载且视觉场景明确报未接入() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();

            AiChatClientRegistry registry = context.getBean(AiChatClientRegistry.class);
            assertSame(textClient, registry.textClient());

            // 关键语义：宁可明确报错，也不回落到文本模型假装看懂了图片
            BizException ex = assertThrows(BizException.class,
                    () -> registry.visionClient(SceneCode.CHAT_IMAGE));
            assertEquals(ErrorCode.BIZ_REJECTED, ex.getErrorCode());
            assertThat(ex.getMessage()).contains("多模态");
        });
    }

    @Test
    void 存在两个ChatClient时应能启动且视觉场景路由到视觉client() {
        runner().withBean("visionChatClient", ChatClient.class, () -> visionClient).run(context -> {
            // 少了 @Qualifier 时，这里会因「expected single matching bean」而失败
            assertThat(context).hasNotFailed();

            AiChatClientRegistry registry = context.getBean(AiChatClientRegistry.class);
            assertSame(textClient, registry.textClient());
            assertSame(visionClient, registry.visionClient(SceneCode.CHAT_IMAGE));
            // 其余需要看图的场景共用同一个视觉 client
            assertSame(visionClient, registry.visionClient(SceneCode.OCR_GLUCOSE));
            assertSame(visionClient, registry.visionClient(SceneCode.MEAL_ANALYSIS));
        });
    }

    @Test
    void 对不需要看图的场景索取视觉client应当场暴露调用方写错() {
        runner().withBean("visionChatClient", ChatClient.class, () -> visionClient).run(context -> {
            AiChatClientRegistry registry = context.getBean(AiChatClientRegistry.class);

            // 这是断言而不是回退：把纯文本场景交给视觉模型属于调用方 bug，
            // 静默返回一个 client 会让「图片被送给纯文本模型」这种事故变得无声无息
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> registry.visionClient(SceneCode.CHAT));
            assertThat(ex.getMessage()).contains("textClient");
        });
    }
}
