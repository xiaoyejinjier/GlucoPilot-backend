package com.threadfit.glucopilot.ai.model.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;

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

    public DynamicChatModel(String channel,
                            AiRuntimeConfigService runtime,
                            ChatModelFactory factory,
                            Function<AiRuntimeConfigService.Snapshot, AiModelSpec> channelDefault,
                            Function<AiRuntimeConfigService.Snapshot, AiModelSpec> yamlChannelSpec,
                            Supplier<ChatModel> yamlFallback) {
        this.channel = channel;
        this.runtime = runtime;
        this.factory = factory;
        this.channelDefault = channelDefault;
        this.yamlChannelSpec = yamlChannelSpec;
        this.yamlFallback = yamlFallback;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return resolve(prompt).call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return resolve(prompt).stream(prompt);
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

    private ChatModel resolve(Prompt prompt) {
        AiRuntimeConfigService.Snapshot snapshot = runtime.snapshot();
        pruneIfConfigChanged(snapshot);

        String requested = requestedModel(prompt);
        if (StringUtils.hasText(requested)) {
            AiModelSpec byName = snapshot.resolveByName(requested);
            if (byName != null) {
                return modelFor(byName);
            }
        }
        return modelFor(channelDefault.apply(snapshot));
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
}
