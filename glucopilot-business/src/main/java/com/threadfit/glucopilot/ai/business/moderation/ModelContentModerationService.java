package com.threadfit.glucopilot.ai.business.moderation;

import com.threadfit.glucopilot.ai.client.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.scene.SceneCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 用大模型判定的内容审核实现。
 *
 * <h2>为什么用 utility client 而不是对话 client</h2>
 *
 * 审核请求必须<b>干净</b>：不能带上对话场景的系统提示词、长期记忆注入、工具调用，也不能被对话侧的 医疗关键词硬拦截改写。{@code utilityClient()} 是一个不挂 Advisor、不挂工具的同模型客户端，
 * 正好满足这个要求；同时它仍然走 {@link AiChatClientRegistry}，模型热切换与凭据解析只有一份知识。
 *
 * <h2>请求级参数从哪来</h2>
 *
 * 提示词来自 {@code ai_prompt_template} 的 {@link SceneCode#MODERATION} 当前生效版本（classpath
 * {@code prompts/moderation/system.md} 兜底），版本标签随结论落库——事后能回答"这条是用哪一版 提示词判的"。温度 0、输出封顶 300 token、关思维链由 {@link
 * SceneModelResolver} 按场景配置给出。
 *
 * <h2>失败一律 ERROR</h2>
 *
 * 见 {@link ContentModerationService} 的接口承诺：模型不可用 / 超时 / 输出不可解析都返回 {@code ERROR}， <b>绝不自动放行</b>。
 */
@Service
public class ModelContentModerationService implements ContentModerationService {

    private static final Logger LOG = LoggerFactory.getLogger(ModelContentModerationService.class);

    /** 单次模型调用上限：审核是后台任务，宁可超时转人工也不要挂着。 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);

    /** 调用不可用时写进 {@code ai_reason} 的文案：告诉运营该做什么，而不是暴露堆栈。 */
    static final String UNAVAILABLE = "AI 初审暂不可用，请重新初审；未自动发布文章";

    private final AiChatClientRegistry clients;

    private final AiPromptTemplateService prompts;

    private final SceneModelResolver sceneModelResolver;

    public ModelContentModerationService(
            AiChatClientRegistry clients,
            AiPromptTemplateService prompts,
            SceneModelResolver sceneModelResolver) {
        this.clients = clients;
        this.prompts = prompts;
        this.sceneModelResolver = sceneModelResolver;
    }

    @Override
    public ModerationResult judge(ModerationRequest request) {
        String promptVersion = promptVersion();
        String system;
        try {
            system = prompts.systemPrompt(SceneCode.MODERATION);
        } catch (RuntimeException ex) {
            LOG.warn("读取审核提示词失败，按不可用处理", ex);
            return new ModerationResult(ModerationVerdict.error(UNAVAILABLE), "", promptVersion);
        }
        Answer answer;
        try {
            answer =
                    Mono.fromCallable(() -> call(system, request))
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(CALL_TIMEOUT)
                            .block();
        } catch (RuntimeException ex) {
            // 失败不放行：写成 ERROR 等人工处理（文章不会被发布，也不会被悄悄判通过）
            LOG.warn("审核模型调用未完成 kind={}", request.kind(), ex);
            return new ModerationResult(ModerationVerdict.error(UNAVAILABLE), "", promptVersion);
        }
        String text = answer == null ? null : answer.text();
        ModerationVerdict verdict = ContentModerationParser.parse(text);
        return new ModerationResult(verdict, answer == null ? "" : answer.modelCode(), promptVersion);
    }

    @Override
    public String promptVersion() {
        try {
            return prompts.versionTag(SceneCode.MODERATION);
        } catch (RuntimeException ex) {
            LOG.warn("读取审核提示词版本失败（忽略）", ex);
            return "";
        }
    }

    /** 一次模型调用的产物：正文 + 真实模型编码（落库用）。 */
    record Answer(String text, String modelCode) {}

    /**
     * 发起一次审核调用。
     *
     * <p>必须用 {@code messages(...)} 而不是 {@code user(...)}：后者会把入参当模板渲染， 正文里的花括号会交给 ST 渲染器当变量表达式，直接把这次审核变成一次异常。
     */
    private Answer call(String system, ModerationRequest request) {
        ChatClient client = clients.utilityClient();
        ChatOptions.Builder<?> options =
                sceneModelResolver.hasOverrides(SceneCode.MODERATION)
                        ? sceneModelResolver.optionsFor(SceneCode.MODERATION)
                        : null;
        var requestSpec =
                client.prompt()
                        .messages(new SystemMessage(system), new UserMessage(request.promptText()));
        if (options != null) {
            requestSpec = requestSpec.options(options);
        }
        var response = requestSpec.call().chatResponse();
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return new Answer(null, "");
        }
        // 模型编码取响应元数据：落库的 ai_model 必须反映"真的被调用的那个模型"，
        // 从配置里猜一个名字会让成本核算与排障得出相反结论
        String modelCode =
                response.getMetadata() == null || response.getMetadata().getModel() == null
                        ? ""
                        : response.getMetadata().getModel();
        return new Answer(response.getResult().getOutput().getText(), modelCode);
    }
}
