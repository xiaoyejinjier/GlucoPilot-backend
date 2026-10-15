package com.threadfit.glucopilot.ai.core;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 模型客户端的路由入口：业务代码说「我要哪个<b>能力</b>」，由这里决定用哪个 provider。
 * <p>
 * <b>为什么是「能力」而不是「场景」</b>：最初这个类只有 {@code client(SceneCode)}，
 * 前提是「一个场景 = 一个 client」。接入多模态后这个前提不再成立——
 * {@link SceneCode#CHAT_IMAGE} 需要<b>两个</b> client：阶段 1 用视觉模型看图，
 * 阶段 2 用文本模型组织回答（复用 {@link SceneCode#CHAT} 的链路）。
 * 若继续用 {@code client(SceneCode)}，就会出现「传 CHAT_IMAGE 拿到视觉 client，
 * 但这个场景最终对用户说的话其实出自文本 client」这种读代码时必然误判的形态。
 * 因此改成两个语义明确的方法，把「阶段」这件事显式化：
 * <ul>
 *   <li>{@link #textClient()}——文本 provider（DeepSeek，Bean 名 {@code textChatClient}）；
 *       纯文本场景，以及两阶段里的<b>阶段 2</b>都用它；</li>
 *   <li>{@link #visionClient(SceneCode)}——视觉 provider（阿里云百炼 qwen-vl，Bean 名
 *       {@code visionChatClient}）；只用于需要看图的场景的<b>阶段 1</b>，
 *       由 {@code ai.vision.enabled} 守卫。</li>
 * </ul>
 * <p>
 * <b>注入必须带 {@code @Qualifier}</b>：容器里有两个 {@code ChatClient} Bean，
 * 不带限定符的注入会在启动期因候选不唯一失败，而不是运行期才暴露。
 * 文本和视觉 client 用 {@link ObjectProvider} 注入，是为了在 {@code ai.vision.enabled=false}
 * （Bean 不存在）时仍能正常启动，把「未接入」留到实际调用时再明确报错。
 */
@Component
public class AiChatClientRegistry {

    private final ObjectProvider<ChatClient> textChatClients;

    /** 视觉 client 可能不存在（未开启或未配凭据），因此不直接注入实例。 */
    private final ObjectProvider<ChatClient> visionChatClients;

    private final SceneModelResolver sceneModelResolver;

    public AiChatClientRegistry(@Qualifier("textChatClient") ObjectProvider<ChatClient> textChatClients,
                               @Qualifier("visionChatClient") ObjectProvider<ChatClient> visionChatClients,
                               SceneModelResolver sceneModelResolver) {
        this.textChatClients = textChatClients;
        this.visionChatClients = visionChatClients;
        this.sceneModelResolver = sceneModelResolver;
    }

    /** 文本模型客户端。 */
    public ChatClient textClient() {
        ChatClient text = textChatClients.getIfAvailable();
        if (text == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "文本 AI 未启用，请联系管理员配置模型服务");
        }
        return text;
    }

    /**
     * 视觉模型客户端。
     * <p>
     * 场景参数不是用来选 client 的（那由方法名决定），而是用来做<b>断言</b>：
     * 传一个本来不需要看图的场景进来属于调用方写错，应当立刻暴露，
     * 而不是悄悄把图片交给一个纯文本模型。
     * <p>
     * 未接入视觉 provider 时抛业务异常而<b>不回落文本模型</b>：
     * 让文本模型「假装」看懂了图片，比明确报错危险得多。
     */
    public ChatClient visionClient(SceneCode scene) {
        if (!scene.isRequiresVision()) {
            throw new IllegalArgumentException(
                    "场景 " + scene.getCode() + " 不需要视觉模型，应使用 textClient()");
        }
        ChatClient vision = visionChatClients.getIfAvailable();
        if (vision == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    "场景 " + scene.getCode() + " 需要多模态模型，当前尚未接入"
                            + "（请检查 ai.vision.enabled 与凭据配置）");
        }
        return vision;
    }

    /** 该场景实际使用的模型编码（落库用）。视觉场景返回视觉模型编码，见 {@link SceneModelResolver}。 */
    public String modelCode(SceneCode scene) {
        return sceneModelResolver.modelCode(scene);
    }

    /** 该场景是否声明了请求级参数覆盖。 */
    public boolean hasOverrides(SceneCode scene) {
        return sceneModelResolver.hasOverrides(scene);
    }

    /** 请求级参数。仅文本场景可用；视觉场景的 options 类型不同，调用会抛异常。 */
    public ChatOptions.Builder<?> optionsBuilder(SceneCode scene) {
        return sceneModelResolver.optionsBuilder(scene);
    }

    /**
     * 视觉场景的请求级参数（OpenAI 兼容协议），供 {@code VisionAnalyzer} 注入。
     *
     * @return 需要写进请求的 options；没有可写参数时返回 {@code null}（继承视觉模型自身的参数）
     */
    public ChatOptions.Builder<?> visionOptionsBuilder(SceneCode scene) {
        return sceneModelResolver.visionOptionsBuilder(scene);
    }
}
