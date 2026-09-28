package com.threadfit.glucopilot.ai.core;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

/**
 * 场景 → {@link ChatClient} 的路由入口：业务代码只说「我要哪个场景」，
 * 由这里决定用哪个 provider 的 client、哪套参数、哪个模型编码。
 * <p>
 * 当前只装配了一个文本 provider（DeepSeek），因此非视觉场景都返回同一个 client。
 * 这是接入多模态 provider 的**唯一改动点**：届时新增一个由
 * {@code @ConditionalOnProperty} 守卫的视觉 {@code ChatClient} Bean，
 * 在 {@link #client(SceneCode)} 里按 {@link SceneCode#isRequiresVision()} 分流，
 * 业务层代码不需要动。
 * <p>
 * 视觉场景在 provider 未接入前**直接抛业务异常**而不是回落到文本模型：
 * 让文本模型「假装」看懂了图片，比明确报错危险得多。
 */
@Component
@RequiredArgsConstructor
public class AiChatClientRegistry {

    private final ChatClient textChatClient;
    private final SceneModelResolver sceneModelResolver;

    public ChatClient client(SceneCode scene) {
        if (scene.isRequiresVision()) {
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    "场景 " + scene.getCode() + " 需要多模态模型，当前尚未接入");
        }
        return textChatClient;
    }

    /** 该场景实际使用的模型编码（落库用）。 */
    public String modelCode(SceneCode scene) {
        return sceneModelResolver.modelCode(scene);
    }

    public boolean hasOverrides(SceneCode scene) {
        return sceneModelResolver.hasOverrides(scene);
    }

    public ChatOptions.Builder<?> optionsBuilder(SceneCode scene) {
        return sceneModelResolver.optionsBuilder(scene);
    }
}
