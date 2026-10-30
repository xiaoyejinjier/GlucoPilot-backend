package com.threadfit.glucopilot.ai.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 模型的<b>能力集合</b>：这个模型能干什么（文本 / 视觉 / 工具调用 / 流式）。
 *
 * <h2>为什么从「用途单选」改成「能力集合」</h2>
 * V45 的 {@code model_type} 只能表达 TEXT / VISION 二选一，够用时很干净，但遇到下面两种情况就表达不了：
 * <ul>
 *   <li>一个多模态模型其实既能看图、也能当文本模型用（qwen3-vl 就是），单选被迫二选一；</li>
 *   <li>场景的前置校验需要问「这个模型支持工具调用吗」，二值字段答不了。</li>
 * </ul>
 * 所以把列从 {@code model_type} 升级为 {@code capability}（逗号分隔的 token 集合），
 * 由 {@link #matches(AiModelCapability)} 做「模型能力 ⊇ 场景要求」的判定。
 *
 * <h2>能力 ≠ 通道</h2>
 * 通道（{@link AiChannel}）回答「连哪里、用什么协议、哪把密钥」；
 * 能力回答「这个模型能干什么」。两者正交：视觉能力可以被视觉通道使用，
 * 也可以被某个场景显式绑定（只要调用方真的发图）。
 *
 * <h2>解析策略：读宽松、写严格</h2>
 * <ul>
 *   <li>{@link #parse(String)}：读 DB 用。空值 → 只有 {@code text}；未知 token 丢弃并交由调用方 WARN，
 *       因为历史行、手改库都可能出现脏值，读路径不能因为一个脏 token 让整个快照加载失败。</li>
 *   <li>{@link #parseStrict(String)}：写入口用。未知 token 直接抛 {@link IllegalArgumentException}，
 *       由后台转成 400——配置错误必须在保存那一刻暴露，不能等线上调用才发现。</li>
 * </ul>
 */
public final class AiModelCapability {

    /** 文本对话 / 摘要 / 记忆抽取 / 审核。 */
    public static final String TEXT = "text";

    /** 图片输入（多模态）。 */
    public static final String VISION = "vision";

    /** 支持工具调用（function calling）。 */
    public static final String TOOL = "tool";

    /** 支持流式输出。 */
    public static final String STREAM = "stream";

    /** 规范顺序：顺序固定才能让 {@link #value()} 稳定（指纹、日志、回显都依赖它）。 */
    private static final List<String> KNOWN = List.of(TEXT, VISION, TOOL, STREAM);

    private static final Set<String> KNOWN_SET = Set.copyOf(KNOWN);

    private static final AiModelCapability TEXT_ONLY = new AiModelCapability(List.of(TEXT));

    private static final AiModelCapability VISION_DEFAULT = new AiModelCapability(List.of(VISION, STREAM));

    private final List<String> tokens;

    private AiModelCapability(List<String> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    /** 纯文本模型（yaml 文本通道兜底、历史 NULL 行的默认值）。 */
    public static AiModelCapability text() {
        return TEXT_ONLY;
    }

    /** 视觉模型的最小能力集：能看图、能流式。 */
    public static AiModelCapability visionDefaults() {
        return VISION_DEFAULT;
    }

    /**
     * 宽松解析：读 DB / 历史数据用。
     * <p>
     * null、空白、只有逗号 → {@link #text()}；未知 token 被丢弃（调用方可根据
     * {@link #tokens()} 与原始串对比后 WARN）；token 顺序按 {@link #KNOWN} 归一，重复自动去重。
     */
    public static AiModelCapability parse(String raw) {
        return parse(raw, false);
    }

    /**
     * 严格解析：后台写入口用。
     *
     * @throws IllegalArgumentException 含有未知 token（附上合法取值，便于前端直接提示）
     */
    public static AiModelCapability parseStrict(String raw) {
        return parse(raw, true);
    }

    private static AiModelCapability parse(String raw, boolean strict) {
        if (raw == null || raw.isBlank()) {
            return TEXT_ONLY;
        }
        Set<String> parsed = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String part : raw.split(",")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            if (KNOWN_SET.contains(token)) {
                parsed.add(token);
            } else if (!unknown.contains(token)) {
                unknown.add(token);
            }
        }
        if (!unknown.isEmpty() && strict) {
            throw new IllegalArgumentException(
                    "未知的能力标记：" + String.join(",", unknown) + "（只能是 " + String.join("/", KNOWN) + "）");
        }
        if (parsed.isEmpty()) {
            return TEXT_ONLY;
        }
        List<String> ordered = new ArrayList<>(KNOWN.size());
        for (String known : KNOWN) {
            if (parsed.contains(known)) {
                ordered.add(known);
            }
        }
        return new AiModelCapability(ordered);
    }

    /** 该模型是否具备某项能力。 */
    public boolean contains(String capability) {
        return capability != null && tokens.contains(capability.trim().toLowerCase(Locale.ROOT));
    }

    /** 是否为视觉（多模态）模型——决定它落在哪个「默认池」。 */
    public boolean vision() {
        return tokens.contains(VISION);
    }

    /** 该模型能否满足 `required` 的全部要求（本集合 ⊇ required）。 */
    public boolean matches(AiModelCapability required) {
        return required == null || tokens.containsAll(required.tokens);
    }

    /**
     * 默认标记的「池」：文本池 / 视觉池。
     * <p>
     * 一个模型同一时刻只能有一个 {@code is_default}，所以它必须只属于一个池；
     * 含 vision 就归视觉池——即使它同时具备 text 能力（文本场景仍可通过场景绑定显式选中它）。
     */
    public String pool() {
        return vision() ? VISION : TEXT;
    }

    /** 规范化后的存储值，例如 {@code "text,tool,stream"}。 */
    public String value() {
        return String.join(",", tokens);
    }

    /** 后台展示文案，例如「文本 / 工具 / 流式」。 */
    public String displayText() {
        List<String> labels = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            labels.add(switch (token) {
                case TEXT -> "文本";
                case VISION -> "视觉";
                case TOOL -> "工具";
                case STREAM -> "流式";
                default -> token;
            });
        }
        return String.join(" / ", labels);
    }

    public List<String> tokens() {
        return tokens;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AiModelCapability capability && tokens.equals(capability.tokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokens);
    }

    @Override
    public String toString() {
        return value();
    }
}
