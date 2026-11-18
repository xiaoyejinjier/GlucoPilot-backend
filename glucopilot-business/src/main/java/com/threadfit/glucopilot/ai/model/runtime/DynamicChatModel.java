package com.threadfit.glucopilot.ai.model.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.ai.support.AiCallContext;

/**
 * <b>可热替换的 ChatModel 委托</b>——后台改配置能"下一条消息就生效"的关键部件。
 *
 * <h2>为什么是"委托 ChatModel"而不是"重建 ChatClient"</h2>
 * 本项目有一条硬约束：文本 client 必须是<b>从自动装配的 {@code ChatClient.Builder} clone 出来的</b>，
 * 因为工具由 {@code ToolCallingAdvisor} 执行，而它只挂在这个 Builder 上——手搓的 client 会
 * <b>静默丢掉全部工具</b>（模型照常回答、只是永远不调工具，返回值与日志都看不出来）。
 * <p>
 * 于是动态化只能落在 ChatModel 这一层：
 * {@code ChatClientAutoConfiguration.chatClientBuilder(..., ChatModel chatModel, ...)} 是<b>单值注入</b>，
 * 我们把本类注册成 {@code @Primary}，它就绑在这个委托上；工具链、Advisor 链、观测全都不受影响。
 * 之后换模型只是换委托内部的引用。
 *
 * <h2>三条不变量</h2>
 * <ol>
 *   <li><b>一次调用只解析一次</b>：{@link #call}/{@link #stream} 进来先定下模型，整个流式过程用它——
 *       绝不允许流到一半换模型；</li>
 *   <li><b>按请求声明的模型名取凭据</b>：请求 options 里可能覆盖了 model，此时必须用那个模型的
 *       baseUrl/key，否则会出现"用 A 的密钥调 B 的模型"；找不到才回落通道默认；</li>
 *   <li><b>yaml 通道复用容器里那份模型</b>：配置没被后台覆盖时不重新构造，保留自动装配带来的
 *       RetryTemplate 与观测等行为（也避免每次刷新都新建 HTTP 客户端）。</li>
 * </ol>
 */
@Slf4j
public class DynamicChatModel implements ChatModel {

    private final String channel;
    private final AiRuntimeConfigService runtime;
    private final ChatModelFactory factory;

    /** 通道默认规格解析（文本通道 / 视觉通道各一个）。 */
    private final Function<AiRuntimeConfigService.Snapshot, AiModelSpec> channelDefault;

    /** yaml 兜底模型（文本通道传容器里自动装配的那份；视觉通道传 null，由工厂按规格构造）。 */
    private final Supplier<ChatModel> yamlFallback;

    /** 本通道在 yaml 里的规格：用于判断"DB 配置是否与 yaml 等价"，等价则复用容器里的模型。 */
    private final Function<AiRuntimeConfigService.Snapshot, AiModelSpec> yamlChannelSpec;

    /** 缓存键 = modelCode + 构建指纹；指纹含密钥哈希，所以换了密钥自然会用新条目。 */
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    private final AtomicLong prunedVersion = new AtomicLong(Long.MIN_VALUE);

    /** 账单出口；懒取，没有实现（单测/无 Key 部署）时静默不记。 */
    private final ObjectProvider<AiCallRecorder> recorder;

    public DynamicChatModel(String channel,
                            AiRuntimeConfigService runtime,
                            ChatModelFactory factory,
                            Function<AiRuntimeConfigService.Snapshot, AiModelSpec> channelDefault,
                            Function<AiRuntimeConfigService.Snapshot, AiModelSpec> yamlChannelSpec,
                            Supplier<ChatModel> yamlFallback) {
        this(channel, runtime, factory, channelDefault, yamlChannelSpec, yamlFallback, null);
    }

    public DynamicChatModel(String channel,
                            AiRuntimeConfigService runtime,
                            ChatModelFactory factory,
                            Function<AiRuntimeConfigService.Snapshot, AiModelSpec> channelDefault,
                            Function<AiRuntimeConfigService.Snapshot, AiModelSpec> yamlChannelSpec,
                            Supplier<ChatModel> yamlFallback,
                            ObjectProvider<AiCallRecorder> recorder) {
        this.channel = channel;
        this.runtime = runtime;
        this.factory = factory;
        this.channelDefault = channelDefault;
        this.yamlChannelSpec = yamlChannelSpec;
        this.yamlFallback = yamlFallback;
        this.recorder = recorder;
    }

    /**
     * 同步调用：成功与失败都记一行账单。
     * <p>
     * 记账放在 try/catch 两头而不是 finally，是因为两者要写的东西不同（成功带用量、
     * 失败带异常类名）——finally 里判定不如直写清楚。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        AiCallContext.Call context = AiCallContext.current();
        AiModelSpec spec = resolveSpec(prompt);
        long startNanos = System.nanoTime();
        try {
            ChatResponse response = modelFor(spec).call(prompt);
            record(context, spec, usageOf(response), startNanos, false, null);
            return response;
        } catch (RuntimeException e) {
            record(context, spec, null, startNanos, false, e);
            throw e;
        }
    }

    /**
     * 流式调用：只在流结束时记一行（成功、失败各一次，绝不重复）。
     * <p>
     * <b>上下文从 Reactor Context 读，不读 ThreadLocal</b>：{@code stream()} 是在<b>订阅</b>时执行的，
     * 而订阅可能发生在别的线程上；ThreadLocal 既跟不过去，也无法在发射/终止跨线程时可靠清理
     * （实测会在线程池里留下上一个请求的场景与用户，下一次调用就被记到别人头上）。
     * 调用方用 {@code .contextWrite(ctx -> ctx.put(AiCallContext.CONTEXT_KEY, call))} 挂上来。
     * <p>
     * <b>为什么用 {@code AtomicBoolean} 去重</b>：部分失败时 {@code doOnError} 与 {@code doFinally}
     * 会各触发一次，两个都写就变成"一次调用两行账单"，报表里的调用数会平白翻倍。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // deferContextual：把"读上下文 + 解析模型 + 计时"全部推到订阅时执行
        return Flux.deferContextual(contextView -> streamIn(AiCallContext.fromContext(contextView), prompt));
    }

    private Flux<ChatResponse> streamIn(AiCallContext.Call context, Prompt prompt) {
        AiModelSpec spec = resolveSpec(prompt);
        long startNanos = System.nanoTime();
        AtomicReference<ModelCallUsage> usage = new AtomicReference<>(ModelCallUsage.empty());
        AtomicBoolean written = new AtomicBoolean(false);
        return modelFor(spec).stream(prompt)
                // 先聚合再判定：最后一个 chunk 常常只带 usage、正文为空，跳过它会丢掉用量
                .doOnNext(chunk -> usage.set(usage.get().merge(usageOf(chunk))))
                .doOnError(error -> {
                    if (written.compareAndSet(false, true)) {
                        record(context, spec, usage.get(), startNanos, true, error);
                    }
                })
                .doFinally(signal -> {
                    if (written.compareAndSet(false, true)) {
                        record(context, spec, usage.get(), startNanos, true, null);
                    }
                });
    }

    /**
     * 默认参数取"通道默认模型"的 options。
     * <p>
     * <b>这是 Spring AI 2.0 唯一的默认参数契约。</b>{@code ChatModel#getDefaultOptions()} 自 2.0 起
     * 是 {@code @Deprecated(forRemoval = true)} 的兼容垫片，其默认实现就是 {@code return getOptions()}，
     * 因此本类<b>只覆写 getOptions()、刻意不覆写它</b>（覆写会多出一条"待删除"告警，且 2.x 后续版本
     * 真删掉接口方法时编译直接失败）。框架侧也只用这一个方法：{@code DefaultChatClientUtils}
     * 每轮请求取 {@code chatModel.getOptions()} 作为基底，{@code mutate()} 之后
     * {@code combineWith(请求级参数)}（请求级非空字段覆盖基底），再注入 toolCallbacks。
     * <p>
     * <b>因此这里必须返回真实 provider 的 options（如 {@code ToolCallingChatOptions}）</b>：
     * 返回空实现会让 {@code mutate()} 退化成 {@code DefaultChatOptions}，
     * {@code instanceof ToolCallingChatOptions.Builder} 判定失败 → {@code ToolCallingManager}
     * 拿不到工具回调，表现成"模型照常回答、永远不调工具、日志无异常"（本项目在假模型测试里踩过）。
     */
    @Override
    public ChatOptions getOptions() {
        return channelModel().getOptions();
    }

    // ------------------------------------------------------------------ 内部

    private ChatModel channelModel() {
        return modelFor(channelDefault.apply(runtime.snapshot()));
    }

    /**
     * 解析本次要用的模型规格。
     * <p>
     * 注意它返回的是 {@link AiModelSpec}（身份 + 端点 + 参数）而不是 {@link ChatModel}：
     * 账单要记「model_code（不可改的身份）」与「model_endpoint（发给厂商的名字）」两个不同的东西，
     * 而从 {@code ChatResponse.getMetadata().getModel()} 只能反推后者——那正是
     * {@code chat_message.model_code} 语义混淆的来历（它其实存的是 endpoint）。
     */
    private AiModelSpec resolveSpec(Prompt prompt) {
        AiRuntimeConfigService.Snapshot snapshot = runtime.snapshot();
        pruneIfConfigChanged(snapshot);

        String requested = requestedModel(prompt);
        if (StringUtils.hasText(requested)) {
            AiModelSpec byName = snapshot.resolveByName(requested);
            if (byName != null) {
                return byName;
            }
        }
        return channelDefault.apply(snapshot);
    }

    private static String requestedModel(Prompt prompt) {
        if (prompt == null || prompt.getOptions() == null) {
            return null;
        }
        return prompt.getOptions().getModel();
    }

    private ChatModel modelFor(AiModelSpec spec) {
        if (yamlFallback != null) {
            AiRuntimeConfigService.Snapshot snapshot = runtime.snapshot();
            boolean reuseYaml = "yaml".equals(spec.source()) || equivalentToYaml(spec, snapshot);
            if (reuseYaml) {
                ChatModel fallback = yamlFallback.get();
                if (fallback != null) {
                    return fallback;
                }
            }
        }
        String key = cacheKey(spec);
        return models.computeIfAbsent(key, ignored -> {
            log.info("通道 {} 构造模型：modelCode={} endpoint={} source={}",
                    channel, spec.modelCode(), spec.modelEndpoint(), spec.source());
            return factory.build(spec);
        });
    }

    /**
     * DB 配置与 yaml 完全等价时，优先复用容器里自动装配的那份模型。
     * <p>
     * 为什么值得这么判一次：自动装配的模型带着 RetryTemplate 与观测等行为，而工厂重建的那份只带
     * 我们能显式拿到的依赖——如果后台只是把同一套参数登记了一遍（V13 种子的情况：只写了模型名，
     * 密钥留空沿用 yaml），却因此静默失去重试能力，那是很难发现的退化。
     */
    private boolean equivalentToYaml(AiModelSpec spec, AiRuntimeConfigService.Snapshot snapshot) {
        AiModelSpec yaml = yamlChannelSpec.apply(snapshot);
        return yaml != null && yaml.buildFingerprint().equals(spec.buildFingerprint());
    }

    /**
     * 配置内容变化时清掉不再需要的条目。
     * <p>
     * 快照版本是<b>内容哈希</b>（见 {@link AiRuntimeConfigService}），所以 TTL 到期但配置没变时不会清缓存——
     * 否则每 60 秒就会重建一次 HTTP 客户端，白白抖动连接池。
     */
    private void pruneIfConfigChanged(AiRuntimeConfigService.Snapshot snapshot) {
        long version = snapshot.version();
        long last = prunedVersion.get();
        if (last == version) {
            return;
        }
        if (!prunedVersion.compareAndSet(last, version)) {
            return;
        }
        Set<String> live = new HashSet<>();
        snapshot.byCode().values().forEach(spec -> live.add(cacheKey(spec)));
        live.add(cacheKey(snapshot.yamlVision()));
        int before = models.size();
        models.keySet().removeIf(key -> !live.contains(key));
        int removed = before - models.size();
        if (removed > 0) {
            log.info("通道 {} 配置已变更，清理 {} 个不再使用的模型实例（在途请求继续用旧实例直到结束）",
                    channel, removed);
        }
    }

    private static String cacheKey(AiModelSpec spec) {
        return spec.modelCode() + "|" + spec.buildFingerprint();
    }

    /** 从响应元数据取用量；任何一层缺失都按「没有用量」处理，绝不让记账影响主链路。 */
    private static ModelCallUsage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return ModelCallUsage.empty();
        }
        try {
            Usage usage = response.getMetadata().getUsage();
            return ModelCallUsage.from(usage);
        } catch (RuntimeException e) {
            return ModelCallUsage.empty();
        }
    }

    /**
     * 写一行账单。这是全仓唯一的模型调用漏斗，所以也是唯一能保证"漏不掉"的位置。
     *
     * <p>记账本身失败只 WARN：账单是观测设施，不能把一次成功的模型调用变成用户的 500。
     */
    private void record(AiCallContext.Call context,
                        AiModelSpec spec,
                        ModelCallUsage usage,
                        long startNanos,
                        boolean stream,
                        Throwable error) {
        AiCallRecorder sink = recorder == null ? null : recorder.getIfAvailable();
        if (sink == null) {
            return;
        }
        try {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            sink.record(AiCallRecord.of(context, channel, spec.modelCode(), spec.modelEndpoint(),
                    usage == null || usage.isEmpty() ? null : usage,
                    latencyMs, stream, error == null,
                    error == null ? null : error.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            log.warn("账单记录失败（忽略）scene={} model={}", context == null ? null : context.scene(),
                    spec.modelCode(), e);
        }
    }
}
