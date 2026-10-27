package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.core.SceneModelResolver;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 社区初审的轮询执行器：每 {@code community.moderation.delay} 毫秒取一条待审内容，交给文本模型判定。
 *
 * <h2>为什么直接用 ChatModel 而不是 ChatClient</h2>
 * 审核请求必须<b>干净</b>：不能带上对话场景的系统提示词、长期记忆注入、工具调用，
 * 也不能被对话侧的医疗关键词硬拦截改写。因此这里绕开 Advisor 链，
 * 直接 {@code ChatModel#call} + 一段独立的审核系统提示词。
 *
 * <h2>提示词从哪来（本次改造的重点）</h2>
 * 提示词原先硬编码在本类的 {@code SYSTEM} 常量里：改一个字要发版、无版本、无回滚。
 * 现在改成从 {@link AiPromptTemplateService} 取 {@link SceneCode#MODERATION} 场景的<b>当前生效版本</b>
 * （库表 {@code ai_prompt_template} 优先、classpath {@code prompts/moderation/system.md} 兜底），
 * 并把版本标签随结论落进 {@code ai_prompt_ver}——事后能回答"这条是用哪一版提示词判的"。
 * 请求级参数（温度 0、输出封顶 300 token、关思维链）由 {@link SceneModelResolver} 按场景配置给出。
 *
 * <h2>失败语义</h2>
 * 模型不可用 / 超时 / 输出不可解析 —— 一律写成 {@code ERROR}，<b>绝不自动放行</b>：
 * 文章进 {@code AI_ERROR} 等人工点"重新初审"，评论保持可见但 {@code ai_verdict=ERROR}（见批量审核实现）。
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "community.moderation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommunityModerationWorker {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CommunityModerationWorker.class);

    /** 单次模型调用上限：审核是后台任务，宁可超时转人工也不要挂着。 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);

    private final CommunityModerationQueue queue;

    private final ObjectProvider<ChatModel> models;

    private final AiPromptTemplateService prompts;

    private final SceneModelResolver sceneModelResolver;

    public CommunityModerationWorker(CommunityModerationQueue queue,
                                     @Qualifier("glucopilotTextChatModel") ObjectProvider<ChatModel> models,
                                     AiPromptTemplateService prompts,
                                     SceneModelResolver sceneModelResolver) {
        this.queue = queue;
        this.models = models;
        this.prompts = prompts;
        this.sceneModelResolver = sceneModelResolver;
    }

    @Scheduled(initialDelayString = "${community.moderation.initial-delay:10000}",
            fixedDelayString = "${community.moderation.delay:5000}")
    public void runOnce() {
        CommunityModerationQueue.Job job;
        try {
            job = queue.claim();
        } catch (RuntimeException ex) {
            LOG.warn("社区初审队列暂不可用", ex);
            return;
        }
        if (job == null) {
            return;
        }
        String promptVer = promptVersion();
        try {
            ChatModel model = models.getIfAvailable();
            if (model == null) {
                throw new IllegalStateException("文本模型不可用（ai.model.chat 未启用或未配置凭据）");
            }
            ChatAnswer answer = Mono.fromCallable(() -> call(model, job))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(CALL_TIMEOUT)
                    .block();
            String text = answer == null ? null : answer.text();
            ModerationVerdict verdict = CommunityModerationParser.parse(text);
            if (verdict.isError()) {
                LOG.warn("社区初审未得到可解析结论 articleId={} revision={} 原因={}",
                        job.id(), job.revision(), verdict.reason());
            }
            queue.finish(job, verdict, answer == null ? "" : answer.modelCode(), promptVer);
        } catch (Exception ex) {
            // 失败不放行：写成 ERROR 等人工处理（文章不会被发布，也不会被悄悄判通过）
            queue.finish(job, ModerationVerdict.error("AI 初审暂不可用，请重新初审；未自动发布文章"),
                    "", promptVer);
            LOG.warn("社区文章初审未完成 articleId={} revision={}", job.id(), job.revision(), ex);
        }
    }

    /** 一次模型调用的产物：正文 + 真实模型编码（落库用）。 */
    record ChatAnswer(String text, String modelCode) {
    }

    /**
     * 发起一次审核调用。
     * <p>
     * 用户消息是<b>标注式纯文本</b>而不是 JSON：模型偶发会把 JSON 输入当成输出模板照抄，
     * 而审核输出协议是逐行字段清单，两者混在一起最容易解析失败。
     */
    private ChatAnswer call(ChatModel model, CommunityModerationQueue.Job job) {
        List<Message> messages = List.of(
                new SystemMessage(prompts.systemPrompt(SceneCode.MODERATION)),
                new UserMessage(payload(job)));
        ChatOptions.Builder<?> options = sceneModelResolver.hasOverrides(SceneCode.MODERATION)
                ? sceneModelResolver.optionsBuilder(SceneCode.MODERATION) : null;
        Prompt prompt = options == null
                ? new Prompt(messages)
                : new Prompt(messages, options.build());
        var response = model.call(prompt);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new ChatAnswer(null, "");
        }
        // 模型编码取响应元数据：落库的 ai_model 必须反映"真的被调用的那个模型"，
        // 从配置里猜一个名字会让成本核算与排障得出相反结论
        String modelCode = response.getMetadata() == null || response.getMetadata().getModel() == null
                ? "" : response.getMetadata().getModel();
        return new ChatAnswer(response.getResult().getOutput().getText(), modelCode);
    }

    /**
     * 送审文本：类型 + 标题 + 标签 + 正文（正文原样，不截断——截断会漏掉长文后半段的违规）。
     * <p>
     * 评论没有标题与标签，只送"类型 + 正文"；类型写进数据里，是因为提示词对两者的容忍度不同
     * （文章后面还有人工终审，评论没有）。
     */
    static String payload(CommunityModerationQueue.Job job) {
        StringBuilder sb = new StringBuilder(job.isComment() ? "类型：评论\n" : "类型：文章\n");
        if (job.title() != null && !job.title().isBlank()) {
            sb.append("标题：").append(job.title().trim()).append('\n');
        }
        if (job.tags() != null && !job.tags().isEmpty()) {
            sb.append("标签：").append(String.join("、", job.tags())).append('\n');
        }
        sb.append("正文：\n").append(job.body() == null ? "" : job.body());
        return sb.toString();
    }

    /** 提示词版本标签（如 moderation@v1）；取不到时返回空串，不影响审核本身。 */
    private String promptVersion() {
        try {
            return prompts.versionTag(SceneCode.MODERATION);
        } catch (RuntimeException ex) {
            LOG.warn("读取审核提示词版本失败（忽略）", ex);
            return "";
        }
    }
}
