package com.threadfit.glucopilot.ai.client;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.scene.SceneCode;

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
 * 因此改成按能力命名的方法，把「阶段」这件事显式化：
 * <ul>
 *   <li>{@link #textClient()}——带工具与记忆的对话客户端（Bean 名 {@code textChatClient}）；
 *       文本场景，以及两阶段里的<b>阶段 2</b>都用它；</li>
 *   <li>{@link #utilityClient()}——无 advisor、无工具的「裸」文本客户端（Bean 名
 *       {@code utilityChatClient}），供摘要、记忆抽取、内容审核、翻译这类「把文本当输入、
 *       不希望被人设/记忆/工具污染」的后台任务使用；</li>
 *   <li>{@link #visionClient(SceneCode)}——视觉 provider（阿里云百炼 qwen-vl，Bean 名
 *       {@code visionChatClient}）；只用于需要看图的场景的<b>阶段 1</b>，
 *       由 {@code ai.vision.enabled} 守卫。</li>
 * </ul>
 * <p>
 * <b>没有「直接给 ChatModel」的出口</b>：曾经有一个 {@code textModel()}，只服务内容审核与翻译
 * 两个调用方；它们现在已和其它业务一样改用 ChatClient 装配（审核搬进
 * {@code ai.business.moderation}、翻译搬进 {@code ai.business.translation}），
 * 于是「怎么拿到模型」只剩上面三条出口，不再有两套说法。
 * <p>
 * <b>注入必须带 {@code @Qualifier}</b>：容器里有多个 {@code ChatClient} / {@code ChatModel} Bean，
 * 不带限定符的注入会在启动期因候选不唯一失败。全部用 {@link ObjectProvider} 注入，是为了在
 * {@code spring.ai.model.chat=none}（无密钥部署）或 {@code ai.vision.enabled=false} 时仍能正常启动，
 * 把「未接入」留到实际调用时再明确报错。
 */
@Component
public class AiChatClientRegistry {

    private final ObjectProvider<ChatClient> textChatClients;

    private final ObjectProvider<ChatClient> utilityChatClients;

    /** 视觉 client 可能不存在（未开启或未配凭据），因此不直接注入实例。 */
    private final ObjectProvider<ChatClient> visionChatClients;

    private final SceneModelResolver sceneModelResolver;

    public AiChatClientRegistry(@Qualifier("textChatClient") ObjectProvider<ChatClient> textChatClients,
                               @Qualifier("utilityChatClient") ObjectProvider<ChatClient> utilityChatClients,
                               @Qualifier("visionChatClient") ObjectProvider<ChatClient> visionChatClients,
                               SceneModelResolver sceneModelResolver) {
        this.textChatClients = textChatClients;
        this.utilityChatClients = utilityChatClients;
        this.visionChatClients = visionChatClients;
        this.sceneModelResolver = sceneModelResolver;
    }

    /** 文本模型客户端（带工具与记忆）。 */
    public ChatClient textClient() {
        ChatClient text = textChatClients.getIfAvailable();
        if (text == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "文本 AI 未启用，请联系管理员配置模型服务");
        }
        return text;
    }

    /**
     * 后台任务使用的「裸」文本客户端（摘要 / 记忆抽取 / 内容审核 / 翻译都走这里）。
     * <p>
     * 与 {@link #textClient()} 分开是<b>刻意</b>的：那个 client 挂着人设提示词、记忆注入与工具，
     * 而这类任务都是「只处理给定的这段文本」，走同一条链路会得到被人设与历史污染的结果。
     */
    public ChatClient utilityClient() {
        ChatClient utility = utilityChatClients.getIfAvailable();
        if (utility == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "文本 AI 未启用，请联系管理员配置模型服务");
        }
        return utility;
    }

    /**
     * utility 客户端是否可用。
     * <p>
     * 摘要与记忆抽取都是<b>后台增强</b>：无密钥部署时它们应当安静跳过，而不是每轮对话抛一次异常。
     * 所以这两处先问可用性、再取实例，把「可选」这件事写在调用点上。
     * <p>
     * 注意「可用」只代表<b>装配层面</b>有这个 Bean：凭据真的能用与否，要调用一次才知道。
     */
    public boolean utilityAvailable() {
        return utilityChatClients.getIfAvailable() != null;
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

    /** 请求级参数。类型由该场景实际会用的协议决定（见 {@link SceneModelResolver#optionsFor}）。 */
    public ChatOptions.Builder<?> optionsFor(SceneCode scene) {
        return sceneModelResolver.optionsFor(scene);
    }

    /** @deprecated 用 {@link #optionsFor(SceneCode)}；保留只为兼容，协议统一后删除。 */
    @Deprecated
    public ChatOptions.Builder<?> optionsBuilder(SceneCode scene) {
        return sceneModelResolver.optionsFor(scene);
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
