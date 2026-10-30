package com.threadfit.glucopilot.ai.core;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AIProperties.SceneProperties;
import com.threadfit.glucopilot.ai.config.AiModelCapability;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.ai.scene.service.AiSceneBindingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.threadfit.glucopilot.ai.model.runtime.ChatModelFactory;
import com.threadfit.glucopilot.ai.model.runtime.DynamicChatModel;

/**
 * 场景 → 模型编码 / 请求级参数的解析器。
 * <p>
 * 取代了早期的 {@code AiModelInfo}：模型编码不再是「全局一个」，而是随场景解析，
 * 落库（{@code chat_message.model_code}）与回显都走这里，保证「落库的模型 = 实际调用的模型」。
 * <p>
 * <b>模型编码必须问运行时配置，不能读 yaml 里的那个字面量</b>：后台（{@code ai_model_config}）
 * 才是文本/视觉模型的真实来源，yaml 只是兜底。2026-10-13 的反馈（后台把默认模型从 flash 换成 pro，
 * 记录里却还写着 flash）就是这条没做全：解析走 DB 了，落库还在读 yaml，两边对不上。
 * <p>
 * 当前 provider 只有 DeepSeek，因此这里直接构造 {@link DeepSeekChatOptions}；
 * 这是本模块内**唯一**允许出现 DeepSeek 类型的地方，接入第二个 provider 时只改这里。
 * <p>
 * <b>视觉场景（{@link SceneCode#isRequiresVision()}）是例外，三个方法都要区别对待：</b>
 * 它们的模型与参数属于<b>另一个 provider</b>，配置在 {@code ai.vision.*}，
 * 并且在 {@code AiVisionClientConfig} 构造 client 时就烤进了 defaultOptions。
 * 若这里不做区分，会得到三种错误的组合：
 * <ul>
 *   <li>{@link #modelCode} 回落成 {@code spring.ai.deepseek.chat.model}——
 *       于是「落库的模型 = 实际调用的模型」这条承诺对视觉场景失效，记录里写的是一个没被调用过的模型；</li>
 *   <li>{@link #hasOverrides} 认为场景声明了覆盖，引导调用方去取 options；</li>
 *   <li>{@link #optionsBuilder} 造出 {@code DeepSeekChatOptions}——而视觉 client 是 OpenAI 协议，
 *       把 DeepSeek 的 options 交给它属于类型层面的错误，是这三者里唯一真会炸的。</li>
 * </ul>
 */
@Slf4j
@Component
public class SceneModelResolver {

    /**
     * 已经废弃的场景级模型钉死键（yaml {@code ai.scenes.<key>.model-code}）。
     * <p>
     * 为什么要废弃：这条链的优先级曾排在「后台用途默认」之上，于是运营在后台把默认模型
     * 换成 B 之后，场景仍然按 yaml 写的 A 去调——「记录写 A、实际调 B」的典型来源。
     * 现在模型只由「场景绑定（DB）→ 通道默认（DB）→ 通道兜底（yaml，仅通道级）」决定，
     * 场景 yaml 只剩参数（温度/上限/提示词），不再参与选模型。
     */
    private static final String DEPRECATED_SCENE_MODEL_CODE_SUFFIX = ".model-code";

    private final AIProperties aiProperties;

    private final AiRuntimeConfigService runtimeConfig;

    /** 场景绑定（{@code ai_scene_config}）：DB 非空字段优先于 yaml。 */
    private final AiSceneBindingService sceneBindings;

    /** 只用于启动时检查老配置里是否还残留着 {@code ai.scenes.<key>.model-code}。 */
    private final Environment environment;

    public SceneModelResolver(AIProperties aiProperties,
                              AiRuntimeConfigService runtimeConfig,
                              AiSceneBindingService sceneBindings,
                              Environment environment) {
        this.aiProperties = aiProperties;
        this.runtimeConfig = runtimeConfig;
        this.sceneBindings = sceneBindings;
        this.environment = environment;
    }

    /**
     * 启动时点出「视觉场景配了 thinking 但发不出去」这种死配置。
     * <p>
     * 视觉通道走 OpenAI 兼容协议，请求级能表达的只有 model/temperature/max-tokens；
     * 百炼的 {@code qwen3-vl-plus} 也没有 {@code thinking} 这个入参。于是
     * {@code ai.scenes.<视觉场景>.thinking} 会被静默忽略——运营以为开了深度思考，
     * 实际发出去的请求里根本没有这个字段。
     * <p>
     * 这里刻意只 WARN 不启动失败：这个键在历史上被写过（{@code meal-analysis.thinking: true}），
     * 让一个无意义的键阻断发版不合理；但必须留一条日志，否则永远没人发现它是死的。
     */
    @PostConstruct
    void warnAboutIgnoredVisionThinking() {
        for (SceneCode scene : SceneCode.values()) {
            if (!scene.isRequiresVision()) {
                continue;
            }
            SceneProperties config = aiProperties.getScenes().get(scene.getConfigKey());
            if (config != null && config.getThinking() != null) {
                log.warn("ai.scenes.{}.thinking={} 不会生效：视觉通道（OpenAI 兼容协议）没有这个入参，"
                                + "请删掉该行以免误导。真正影响视觉模型是否推理的是模型自身的部署参数",
                        scene.getConfigKey(), config.getThinking());
            }
        }
    }

    /**
     * 启动时点出「场景还在 yaml 里钉死模型」这种已废弃配置。
     * <p>
     * 这个键本身已经不再参与解析（场景 yaml 只保留参数），但老部署里可能存在：
     * 既然它已经不生效，就必须在启动日志里说清楚，否则运维会以为改了 yaml 仍然有效。
     * 刻意只 WARN：一个已废弃键不该阻断发版。
     */
    @PostConstruct
    void warnAboutDeprecatedSceneModelCode() {
        for (SceneCode scene : SceneCode.values()) {
            String key = "ai.scenes." + scene.getConfigKey() + DEPRECATED_SCENE_MODEL_CODE_SUFFIX;
            String legacy = environment.getProperty(key);
            if (StringUtils.hasText(legacy)) {
                log.warn("{}={} 已废弃且不再生效：场景不再从 yaml 选模型，"
                                + "请在后台「场景配置」给 {} 绑定模型，或在「模型配置」里设置通道默认模型",
                        key, legacy, scene.getCode());
            }
        }
    }

    /**
     * 场景实际使用的模型编码。
     * <p>
     * 视觉场景返回的是视觉模型（阶段 1 真正调用的那个），而不是回落成 DeepSeek 的名字——
     * 否则落库的 {@code chat_message.model_code} 会与事实不符，用于成本核算时会算错账。
     * <p>
     * 文本场景返回 {@link AiRuntimeConfigService#textSpec} 的结果：场景绑定 → 后台默认 → yaml 通道兜底，
     * 与 {@code DynamicChatModel} 真正拿去发请求的那一份同源。注意 yaml 的
     * {@code ai.scenes.<scene>.model-code} 已<b>不再参与</b>（见 {@link #warnAboutDeprecatedSceneModelCode()}）。
     * <p>
     * 场景绑定（{@code ai_scene_config.model_code}）**只有在该编码仍然可解析、且能力满足场景要求时才采用**：
     * 后台禁用/删掉一个模型后若还按它落库，就会出现"记录里写 A、实际调 B"的老问题（2026-10-13 的反馈）。
     */
    public String modelCode(SceneCode scene) {
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        if (scene.isRequiresVision()) {
            // 视觉场景也认后台绑定（批次 2 之前这里是硬编码 visionSpec()，绑定"存了不用"）：
            // 只有解析到「具备视觉能力」的规格才采纳，否则宁可回落通道视觉模型——
            // 把一个纯文本模型塞进视觉请求，结果一定是 400/404，而且错误信息与"绑定没生效"长得一样。
            if (binding.hasModel()) {
                AiModelSpec bound = runtimeConfig.specByModelCode(binding.modelCode());
                if (bound != null && bound.vision()) {
                    return bound.modelCode();
                }
                log.warn("视觉场景 {} 绑定的模型编码 {} 不可用（未登记、已停用，或能力不含 vision），"
                                + "回落通道视觉模型",
                        scene.getCode(), binding.modelCode());
            }
            return runtimeConfig.visionSpec().modelCode();
        }
        if (binding.hasModel()) {
            AiModelSpec bound = runtimeConfig.specByModelCode(binding.modelCode());
            if (bound != null && bound.capability().contains(AiModelCapability.TEXT)) {
                return bound.modelCode();
            }
            log.warn("场景 {} 在后台绑定的模型编码 {} 不可用（不存在、已停用，或能力不含 text），"
                            + "回落通道默认模型",
                    scene.getCode(), binding.modelCode());
        }
        return runtimeConfig.textSpec(scene).modelCode();
    }

    /**
     * 场景是否声明了请求级参数覆盖。
     * <p>
     * 视觉场景：只有 {@link #visionOptionsBuilder} 真的会写参数时才算有覆盖——
     * 它的 model/temperature/max-tokens 默认来自 {@code ai.vision.*}，已由 {@code ChatModelFactory}
     * 烤进视觉模型自身的 options，不再走请求级。
     */
    public boolean hasOverrides(SceneCode scene) {
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        SceneProperties config = aiProperties.scene(scene);
        if (scene.isRequiresVision()) {
            // 视觉场景的请求级参数只有三个字段（model / max-completion-tokens / temperature）：
            // 任一来源（后台绑定、yaml 场景参数）能给出其中一个，就值得构造 options；
            // 都给不出时返回 false，让请求直接继承视觉模型自身的参数。
            return binding.hasModel() || binding.temperature() != null || binding.maxTokens() != null
                    || config.getTemperature() != null || config.getMaxTokens() != null;
        }
        if (binding.hasModel() || binding.hasParams()) {
            return true;
        }
        return config.getTemperature() != null
                || config.getMaxTokens() != null
                || config.getThinking() != null;
    }

    /**
     * 构建请求级参数（返回 Builder：{@code ChatClient.prompt().options(...)} 接收的就是 Builder）。
     * <p>
     * 只写场景显式声明的字段，未声明的留空，由 Spring AI 与 {@code spring.ai.deepseek.chat.*}
     * 的默认值合并，避免同一份参数在两处维护。
     * <p>
     * <b>取值优先级：后台场景配置（{@code ai_scene_config} 非空字段） &gt; yaml
     * {@code ai.scenes.<scene>.*}</b>。整段留给运营的价值就在这：改温度/上限不用重新打包。
     * <p>
     * <b>场景绑定了 {@code model-code} 时，写进去的是该配置的 {@code model_endpoint} 而不是编码本身</b>：
     * 编码是业务起的名字（可以叫 "glucopilot-chat"），真正发给厂商的必须是端点名。
     * 早先直接写编码，在"编码 ≠ 端点"的配置上会既调错模型、又用错凭据。
     * 编码没登记（或已被停用）时不写 model 并 WARN，交给通道默认——与
     * {@link AiRuntimeConfigService#textSpec} 的回落保持一致，避免"记录里写一个、实际调另一个"。
     * <p>
     * 视觉场景直接拒绝：该方法只能造出 DeepSeek 的 options，交给 OpenAI 协议的视觉 client
     * 是类型层面的错误。视觉参数请用 {@link #visionOptionsBuilder(SceneCode)}。
     */
    public ChatOptions.Builder<?> optionsBuilder(SceneCode scene) {
        if (scene.isRequiresVision()) {
            throw new IllegalArgumentException("场景 " + scene.getCode()
                    + " 是视觉场景，参数请走 visionOptionsBuilder(...)（OpenAI 协议），"
                    + "不能通过请求级 DeepSeek options 传入");
        }
        SceneProperties config = aiProperties.scene(scene);
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        String modelCode = binding.hasModel() ? binding.modelCode() : null;
        Double temperature = binding.temperature() != null ? binding.temperature() : config.getTemperature();
        Integer maxTokens = binding.maxTokens() != null ? binding.maxTokens() : config.getMaxTokens();
        Boolean thinking = binding.thinking() != null ? binding.thinking() : config.getThinking();

        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder();
        if (StringUtils.hasText(modelCode)) {
            AiModelSpec bound = runtimeConfig.specByModelCode(modelCode);
            if (bound != null) {
                builder.model(bound.modelEndpoint());
            } else {
                log.warn("场景 {} 绑定的模型编码 {} 不在运行时配置里（不存在或已停用），"
                                + "本次不指定模型，交由通道默认模型处理",
                        scene.getCode(), modelCode);
            }
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        if (thinking != null) {
            if (thinking) {
                builder.enableThinking();
            } else {
                builder.disableThinking();
            }
        }
        return builder;
    }

    /**
     * 视觉场景的请求级参数（OpenAI 兼容协议）。
     * <p>
     * <b>只写三个字段：model / max-completion-tokens / temperature。</b>
     * 视觉通道的 {@code thinking} 不在这里——OpenAI 兼容协议用 {@code reasoning_effort} 表达推理，
     * 而百炼的视觉模型并不认，硬发只会 400。若有人配了 {@code ai.scenes.<视觉场景>.thinking}，
     * 启动时 {@link #warnAboutIgnoredVisionThinking()} 会打 WARN，而不是让它静默失效。
     *
     * @return 需要写进请求的 options；没有任何要写的参数时返回 {@code null}（保持模型自身的默认）
     */
    public ChatOptions.Builder<?> visionOptionsBuilder(SceneCode scene) {
        if (!scene.isRequiresVision()) {
            throw new IllegalArgumentException("场景 " + scene.getCode()
                    + " 不是视觉场景，参数请走 optionsBuilder(...)（DeepSeek 协议）");
        }
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        SceneProperties config = aiProperties.scene(scene);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        // 后台绑定指定了视觉模型时优先用它：没有这条，视觉场景的绑定只对参数有效、对模型永远无效
        String boundEndpoint = boundVisionEndpoint(binding);
        if (boundEndpoint != null) {
            builder.model(boundEndpoint);
        }
        Double temperature = binding.temperature() != null ? binding.temperature() : config.getTemperature();
        Integer maxTokens = binding.maxTokens() != null ? binding.maxTokens() : config.getMaxTokens();
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (maxTokens != null) {
            builder.maxCompletionTokens(maxTokens);
        }
        if (boundEndpoint == null && temperature == null && maxTokens == null) {
            // 三个字段都给不出时：继承视觉模型自身的参数，不额外发请求级 options
            return null;
        }
        return builder;
    }

    /**
     * 场景绑定里指定的、可用的视觉模型端点；没有绑定/不可用/用途不是视觉时返回 null。
     * <p>
     * 这里只解析端点、不在这里 WARN：不可用的情况 {@link #modelCode(SceneCode)} 已经 WARN 过一次，
     * 同一次请求里重复两条相同告警只会稀释日志。
     */
    private String boundVisionEndpoint(AiSceneBindingService.SceneBinding binding) {
        if (!binding.hasModel()) {
            return null;
        }
        AiModelSpec bound = runtimeConfig.specByModelCode(binding.modelCode());
        return bound != null && bound.vision() ? bound.modelEndpoint() : null;
    }

    /**
     * 长期记忆抽取的请求级参数（③ 期）。
     * <p>
     * 与 {@link #summaryOptionsBuilder()} 同理：抽取是"从原文里挑事实"，不是推理，
     * 因此<b>关掉思维链</b>并给输出封顶（{@code ai.memory.memory-extract-max-tokens}）；
     * 温度取场景配置（要稳定抽取、不要发挥）。
     * 模型由 MEMORY 场景的绑定/通道默认决定，可在后台单独绑一个更便宜的。
     * <p>
     * 为什么它的输出上限比摘要更小：抽取的产物是几条结构化 JSON（五类键 + 短句），
     * 给大额度只会让模型在 JSON 外面多写解释文字，反而降低解析成功率。
     */
    public ChatOptions.Builder<?> memoryOptionsBuilder() {
        SceneCode scene = SceneCode.MEMORY;
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        SceneProperties config = aiProperties.scene(scene);
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder().disableThinking();
        Double temperature = binding.temperature() != null ? binding.temperature() : config.getTemperature();
        if (temperature != null) {
            builder.temperature(temperature);
        }
        builder.maxTokens(aiProperties.getMemory().getMemoryExtractMaxTokens());
        return builder;
    }

    /**
     * 摘要场景的请求级参数（② 期）。
     * <p>
     * 与普通文本场景的差别，都是为了"别让摘要本身把预算与时延吃掉"：
     * <ul>
     *   <li><b>关掉思维链</b>：摘要是压缩原文而不是推理，开思维链只会让首 token 更慢
     *       ——实测 6000 字输入叠加思维链会直接读超时，任务失败、摘要一直建不起来；</li>
     *   <li><b>输出封顶</b>（{@code ai.memory.summary-max-tokens}）：摘要正文只有几百字，
     *       给上限可以杜绝"模型长篇发挥"拖长生成时间；</li>
     *   <li>温度取场景配置（后台 {@code ai_scene_config} 优先，其次 yaml）：要稳定复述而不是发挥。</li>
     * </ul>
     * 模型本身不在这里指定：它由 SUMMARY 场景的绑定/通道默认决定，因此管理员可以在后台给这个场景
     * 单独绑一个更便宜的模型（这正是"把摘要做成独立场景"的价值之一）。
     */
    public ChatOptions.Builder<?> summaryOptionsBuilder() {
        SceneCode scene = SceneCode.SUMMARY;
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        SceneProperties config = aiProperties.scene(scene);
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder().disableThinking();
        Double temperature = binding.temperature() != null ? binding.temperature() : config.getTemperature();
        if (temperature != null) {
            builder.temperature(temperature);
        }
        builder.maxTokens(aiProperties.getMemory().getSummaryMaxTokens());
        return builder;
    }
}
