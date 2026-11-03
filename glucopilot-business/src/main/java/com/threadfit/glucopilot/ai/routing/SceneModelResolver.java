package com.threadfit.glucopilot.ai.routing;
import com.threadfit.glucopilot.ai.scene.SceneCode;

import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import com.threadfit.glucopilot.ai.config.properties.AIProperties.SceneProperties;
import com.threadfit.glucopilot.ai.model.capability.AiModelCapability;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.ai.scene.service.AiSceneBindingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.threadfit.glucopilot.ai.model.runtime.ChatModelFactory;
import com.threadfit.glucopilot.ai.model.runtime.DynamicChatModel;

import java.util.Map;

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
 * 当前只有 OpenAI 兼容一种协议（协议统一 2026-11），文本与视觉共用 {@link OpenAiChatOptions}；
 * DeepSeek 专用类型已从本类移除。
 * <p>
 * <b>视觉场景（{@link SceneCode#isRequiresVision()}）仍要区别对待：</b>
 * 它们与文本场景共用同一个协议，但参数来源不同（{@code ai.scenes.meal-analysis.*} 等），
 * 且百炼的视觉模型不认 {@code thinking} 这个私有扩展——硬发只会 400。
 * 若这里不做区分，{@link #modelCode} 会回落成文本默认模型，
 * 于是「落库的模型 = 实际调用的模型」这条承诺对视觉场景失效。
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
        return resolveModelSpec(scene).modelCode();
    }

    /**
     * 场景绑定的判定结果。
     *
     * @param binding 绑定原样；未配置时是 {@link AiSceneBindingService.SceneBinding#INHERIT}
     * @param spec    绑定编码解析出的规格；编码不存在或已停用时为 {@code null}
     * @param usable  能否被本场景采用（已解析 <b>且</b> 能力满足场景要求）
     */
    public record BindingJudgement(AiSceneBindingService.SceneBinding binding, AiModelSpec spec, boolean usable) {

        /** 后台是否配了模型编码（不论该编码是否可用）。 */
        public boolean bound() {
            return binding.hasModel();
        }

        /** 后台配的编码；未配置时为 {@code null}。 */
        public String modelCode() {
            return bound() ? binding.modelCode() : null;
        }
    }

    /**
     * 判定场景绑定是否可用：<b>解析 + 能力校验只此一处</b>。
     * <p>
     * 抽出来的原因：{@link #resolveModelSpec} 与 {@code AiRuntimeStatusServiceImpl}（诊断接口）
     * 需要同一个结论，而后者不能打日志（诊断接口会被反复调用）。此前两边各写一份条件，
     * 规则一漂移就会出现"接口说 binding、实际走 default"——这正是这次要消掉的隐患。
     * <p>
     * 刻意<b>不打日志</b>：日志留给真正调用模型的 {@link #resolveModelSpec}。
     */
    public BindingJudgement judgeBinding(SceneCode scene) {
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        if (!binding.hasModel()) {
            return new BindingJudgement(binding, null, false);
        }
        AiModelSpec spec = runtimeConfig.specByModelCode(binding.modelCode());
        boolean usable = spec != null && (scene.isRequiresVision()
                ? spec.vision()
                : spec.capability().contains(AiModelCapability.TEXT));
        return new BindingJudgement(binding, spec, usable);
    }

    /**
     * 场景实际使用的模型规格（不只是编码）。
     * <p>
     * 这是「模型」这条链上<b>唯一</b>的解析入口：{@link #modelCode}（落库/回显）、
     * {@link #optionsFor}（决定 options 类型）、{@link #hasOverrides} 都从它出发，
     * 保证「记录里写的模型」「真正发出去的模型」「参数按哪个协议拼」三件事同源。
     * <p>
     * 采用条件见 {@link #judgeBinding}：后台绑定<b>必须仍可解析、且能力满足场景要求</b>才采纳；
     * 否则回落通道默认（文本 → {@code textSpec(scene)}，视觉 → {@code visionSpec()}）并 WARN。
     */
    public AiModelSpec resolveModelSpec(SceneCode scene) {
        BindingJudgement judgement = judgeBinding(scene);
        if (judgement.usable()) {
            return judgement.spec();
        }
        if (judgement.bound()) {
            log.warn("场景 {} 后台绑定的模型编码 {} 不可用（未登记、已停用，或能力不匹配），"
                            + "本次回落通道默认模型",
                    scene.getCode(), judgement.modelCode());
        }
        return scene.isRequiresVision() ? runtimeConfig.visionSpec() : runtimeConfig.textSpec(scene);
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
        return optionsFor(scene);
    }

    /**
     * 场景的请求级参数。
     * <p>
     * 这是「参数」这条链上唯一的对外入口：先用 {@link #resolveModelSpec(SceneCode)} 拿到模型，
     * 再按该模型造 options。协议统一（2026-11）之后只有 OpenAI 兼容一种，因此不存在
     * 「模型用 A、参数按 B 的协议发」的可能。
     * <p>
     * 取值优先级仍是：后台绑定（{@code ai_scene_config} 非空字段）&gt; yaml {@code ai.scenes.<scene>.*}；
     * 绑定里的 {@code model_code} 写进 options 时用的是 {@code model_endpoint}（编码是业务起的名字）。
     *
     * @return 请求级参数；视觉场景没有任何可写参数时返回 {@code null}（继承模型自身参数）
     */
    public ChatOptions.Builder<?> optionsFor(SceneCode scene) {
        return openAiOptions(scene, resolveModelSpec(scene));
    }

    /**
     * OpenAI 兼容协议的请求级参数。
     * <p>
     * thinking 在 OpenAI 规范里没有对应字段，只能作为私有扩展塞进请求体（{@code extraBody}）。
     * 视觉场景<b>不写</b>它——百炼的视觉模型不认这个字段，硬发只会 400。
     */
    private ChatOptions.Builder<?> openAiOptions(SceneCode scene, AiModelSpec spec) {
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        SceneProperties config = aiProperties.scene(scene);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();

        boolean modelSet = false;
        String boundEndpoint = boundEndpointFor(scene, binding);
        if (boundEndpoint != null) {
            // 没有这条，视觉场景的绑定只对参数有效、对模型永远无效
            builder.model(boundEndpoint);
            modelSet = true;
        }
        Double temperature = binding.temperature() != null ? binding.temperature() : config.getTemperature();
        Integer maxTokens = binding.maxTokens() != null ? binding.maxTokens() : config.getMaxTokens();
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (maxTokens != null) {
            builder.maxCompletionTokens(maxTokens);
        }
        boolean thinkingApplied = false;
        if (!scene.isRequiresVision()) {
            Boolean thinking = binding.thinking() != null ? binding.thinking() : config.getThinking();
            if (thinking != null) {
                builder.extraBody(Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled")));
                thinkingApplied = true;
            }
        }
        if (boundEndpoint == null && temperature == null && maxTokens == null && !thinkingApplied) {
            // 三个字段都给不出时：继承模型自身的参数，不额外发请求级 options
            return null;
        }
        if (!modelSet && spec != null) {
            // 必须显式钉住模型：OpenAiChatOptions.builder() 会预填 SDK 默认模型名，
            // 这个默认值会被 DynamicChatModel 当成"调用方指定了模型"而原样发给厂商
            // （实测发到 DeepSeek 端点是 400：The supported API model names are ... but you passed gpt-5-mini）。
            builder.model(spec.modelEndpoint());
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
                    + " 不是视觉场景，参数请走 optionsFor(...)");
        }
        return optionsFor(scene);
    }

    /**
     * 场景绑定里指定的、可用于该场景的模型端点；没有绑定/不可用/能力不匹配时返回 null。
     * <p>
     * 这里只解析端点、不在这里 WARN：不可用的情况 {@link #resolveModelSpec(SceneCode)} 已经 WARN 过一次，
     * 同一次请求里重复两条相同告警只会稀释日志。
     */
    private String boundEndpointFor(SceneCode scene, AiSceneBindingService.SceneBinding binding) {
        if (!binding.hasModel()) {
            return null;
        }
        AiModelSpec bound = runtimeConfig.specByModelCode(binding.modelCode());
        if (bound == null) {
            return null;
        }
        boolean usable = scene.isRequiresVision()
                ? bound.vision()
                : bound.capability().contains(AiModelCapability.TEXT);
        return usable ? bound.modelEndpoint() : null;
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
        return quietOptions(SceneCode.MEMORY, aiProperties.getMemory().getMemoryExtractMaxTokens());
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
        return quietOptions(SceneCode.SUMMARY, aiProperties.getMemory().getSummaryMaxTokens());
    }

    /**
     * 「关掉思维链 + 输出封顶」的请求级参数。
     * <p>
     * 摘要与记忆抽取共用这条规则：它们是压缩/抽取而不是推理，开思维链只会让首 token 更慢，
     * 实测 6000 字输入叠加思维链会直接读超时。视觉场景不会调用它。
     * <p>
     * thinking 在 OpenAI 规范里没有对应字段，只能走 {@code extraBody}——这一步不能省，
     * 否则摘要会突然带上思维链，时延与超时行为都会变。
     */
    private ChatOptions.Builder<?> quietOptions(SceneCode scene, int maxTokens) {
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        SceneProperties config = aiProperties.scene(scene);
        Double temperature = binding.temperature() != null ? binding.temperature() : config.getTemperature();
        AiModelSpec spec = resolveModelSpec(scene);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                // 同 openAiOptions：不显式写 model 就会被 SDK 默认值（gpt-5-mini）顶替
                .model(spec.modelEndpoint())
                .maxCompletionTokens(maxTokens)
                .extraBody(Map.of("thinking", Map.of("type", "disabled")));
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder;
    }
}
