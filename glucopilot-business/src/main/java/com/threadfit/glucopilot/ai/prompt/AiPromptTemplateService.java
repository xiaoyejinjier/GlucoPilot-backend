package com.threadfit.glucopilot.ai.prompt;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板管理（Spring AI 2.0 的 {@link PromptTemplate} + {@code StTemplateRenderer}）。
 *
 * <h2>来源与优先级</h2>
 * <b>库表 {@code ai_prompt_template} 启用版本 &gt; classpath 内置提示词</b>。
 * 两边的文本都必须是「可渲染的」才会被采用，且 classpath 那一份在启动期就校验过（fail-fast）：
 * <ul>
 *   <li>库表里没有该场景的启用版本（例如还没跑 V18 迁移、或运营把版本停用了）→ 用内置提示词；</li>
 *   <li>库表里那一版<b>校验不通过</b>（缺变量、正文为空）→ <b>WARN 并退回内置提示词</b>，
 *       而不是让线上拿着坏提示词跑。绕过后台直接写库、或迁移脚本有问题时，这一条是最后一道闸。</li>
 * </ul>
 * 「库表空 → 行为与引入后台前一模一样」是本次改造的上线前提，因此上面两条都刻意做成静默降级 + 告警。
 *
 * <h2>刷新</h2>
 * 内容指纹 + 60 秒 TTL；后台保存/启用后由 {@code AdminAiPromptService} 调 {@link #refreshNow()}
 * 让本实例立刻生效，其它实例最多滞后 60 秒。取快照是一次 {@link AtomicReference#get()}，
 * 在 SSE 流式链路上不会成为热点。
 *
 * <h2>模板语法要点（写提示词的人必读）</h2>
 * 模板使用 ST 语法 {@code {变量名}}，因此正文里<b>不要出现成对花括号</b>
 * （例如裸 JSON 示例 {@code {"a":1}}），否则会被当成变量表达式。
 * 需要描述 JSON 契约时请用字段清单。变量清单存在库表的 {@code variables_json} 里
 * （内置提示词则看 {@code ai.scenes.<scene>.variables}），保存前与加载时都会按它试渲染一次。
 */
@Slf4j
@Component
public class AiPromptTemplateService {

    /** 快照有效期：本实例后台改完立即生效，其他实例最多滞后这么久。 */
    private static final Duration REFRESH_TTL = Duration.ofSeconds(60);

    /** {@code chat_session.system_prompt_ver} 是 VARCHAR(32)，版本标签必须做长度保护。 */
    private static final int TAG_MAX_LENGTH = 32;

    /** 从 {@code variables_json} 里抽取变量名：宽容解析（只认双引号里的名字），解析不动就交给试渲染报错。 */
    private static final Pattern JSON_STRING = Pattern.compile("\"([^\"\\\\]+)\"");

    private final AIProperties aiProperties;

    /** 库表读写；<b>允许为 null</b>——单测与不连库的 live 测试只用 classpath 内置提示词。 */
    private final AiPromptTemplateMapper mapper;

    /** 变量语法用框架默认的 {@code {var}}；校验模式显式设为 THROW，缺变量时立刻报错而不是静默产出半截提示词。 */
    private final TemplateRenderer renderer = StTemplateRenderer.builder()
            .validationMode(ValidationMode.THROW)
            .build();

    /** classpath 内置提示词：启动期加载并试渲染校验，作为库表不可用时的兜底。 */
    private final Map<SceneCode, Loaded> builtin = new EnumMap<>(SceneCode.class);

    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    private final Object loadLock = new Object();

    private volatile long loadedAtNanos;

    private volatile String loadedFingerprint;

    private long versionCounter;

    public AiPromptTemplateService(AIProperties aiProperties, AiPromptTemplateMapper mapper) {
        this.aiProperties = aiProperties;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        // 先加载内置提示词：它既是兜底，也是"提示词写坏了就启动失败"这条既有语义的承载体
        for (SceneCode scene : SceneCode.values()) {
            builtin.put(scene, loadBuiltin(scene));
        }
        // 再尝试读库表：读不到（表不存在/库不可达/没有启用版本）都不是启动失败的理由
        load();
    }

    // ------------------------------------------------------------------ 对外读取

    /** 场景当前生效的系统提示词原文（未渲染）。 */
    public String systemPrompt(SceneCode scene) {
        return resolve(scene).text();
    }

    /** 场景当前生效的提示词模板，需要变量时用 {@link PromptTemplate#render(Map)}。 */
    public PromptTemplate template(SceneCode scene) {
        return resolve(scene).template();
    }

    /** 按变量渲染系统提示词。 */
    public String render(SceneCode scene, Map<String, Object> variables) {
        return resolve(scene).template().render(variables);
    }

    /** 注入 ChatClient 的渲染器（与模板同源，避免两处各配一套语法）。 */
    public TemplateRenderer renderer() {
        return renderer;
    }

    /**
     * 提示词版本标签，用于 {@code chat_session.system_prompt_ver}。
     * <p>
     * 库表版本与内置版本的标签形态刻意保持一致（{@code chat@v1}）：
     * 这样历史会话记录不会因为「提示词挪进后台」而在同一个维度上出现两种写法。
     */
    public String versionTag(SceneCode scene) {
        return resolve(scene).versionTag();
    }

    /** 当前生效来源，用于日志与后台排障：{@code db#12}（库表行 id）或 {@code classpath}。 */
    public String source(SceneCode scene) {
        return resolve(scene).source();
    }

    /** 后台保存/启用后调用：本实例立即生效，不必等 TTL。 */
    public void refreshNow() {
        synchronized (loadLock) {
            load();
        }
    }

    /** 当前快照里的库表版本（场景 → 行 id），后台排障用。 */
    public Map<String, Long> dbSnapshotIds() {
        Map<String, Long> ids = new LinkedHashMap<>();
        snapshot().byScene().forEach((scene, loaded) -> ids.put(scene.getCode(), loaded.rowId()));
        return ids;
    }

    // ------------------------------------------------------------------ 校验（后台保存前调用）

    /**
     * 按变量清单试渲染一次模板。
     * <p>
     * 提示词正文里出现未声明的 {@code {xxx}}（典型是粘贴进来的 JSON 示例）会在这里抛出
     * {@link IllegalArgumentException}，由后台接口转成 400 而不是让线上某个请求才炸。
     *
     * @param text      提示词正文
     * @param variables 变量名清单，可为空
     * @param where     报错信息里的位置描述（场景/模板名），便于运营定位
     */
    public static void validateRenderable(String text, List<String> variables, String where) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("提示词正文不能为空：" + where);
        }
        Map<String, Object> probe = new LinkedHashMap<>();
        if (variables != null) {
            for (String variable : variables) {
                if (StringUtils.hasText(variable)) {
                    probe.put(variable.trim(), "probe");
                }
            }
        }
        try {
            PromptTemplate.builder()
                    .template(text)
                    .renderer(ST_RENDERER_FOR_VALIDATION)
                    .build()
                    .render(probe);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("提示词模板校验失败（" + where
                    + "）：正文里的花括号必须是已声明的变量，JSON 示例请改成字段清单。"
                    + "原始错误：" + e.getMessage(), e);
        }
    }

    /** 校验用渲染器（与实例用的是同一套语法，单独放一份是为了让校验是静态工具方法）。 */
    private static final TemplateRenderer ST_RENDERER_FOR_VALIDATION = StTemplateRenderer.builder()
            .validationMode(ValidationMode.THROW)
            .build();

    // ------------------------------------------------------------------ 快照

    private Loaded resolve(SceneCode scene) {
        Loaded fromDb = snapshot().byScene().get(scene);
        if (fromDb != null) {
            return fromDb;
        }
        Loaded fallback = builtin.get(scene);
        if (fallback == null) {
            // 只可能发生在 init() 之外的意外用法（比如手工 new 出来没走 Spring 生命周期）
            throw new IllegalStateException("场景 " + scene.getCode()
                    + " 没有可用提示词：库表未启用任何版本，classpath 内置提示词也未加载");
        }
        return fallback;
    }

    private Snapshot snapshot() {
        Snapshot existing = current.get();
        if (existing == null || isStale()) {
            synchronized (loadLock) {
                if (current.get() == null || isStale()) {
                    load();
                }
            }
        }
        Snapshot latest = current.get();
        return latest == null ? new Snapshot(0, Map.of()) : latest;
    }

    private boolean isStale() {
        return System.nanoTime() - loadedAtNanos > REFRESH_TTL.toNanos();
    }

    private void load() {
        if (mapper == null) {
            return;
        }
        Snapshot previous = current.get();
        try {
            Map<SceneCode, Loaded> byScene = new EnumMap<>(SceneCode.class);
            for (AiPromptTemplateEntity row : mapper.selectEnabled()) {
                SceneCode scene = SceneCode.findByCode(row.getSceneCode());
                if (scene == null) {
                    log.warn("库表提示词场景编码无法识别，已忽略：scene_code={} id={}", row.getSceneCode(), row.getId());
                    continue;
                }
                if (byScene.containsKey(scene)) {
                    // selectEnabled 已按 version_no DESC 排序，所以先到的是版本号更大的那一版
                    log.warn("场景 {} 存在多个启用版本，采用版本号较大的一个（已忽略 id={} version={}）",
                            scene.getCode(), row.getId(), row.getVersionNo());
                    continue;
                }
                Loaded loaded = toLoaded(scene, row);
                if (loaded != null) {
                    byScene.put(scene, loaded);
                }
            }

            String fingerprint = fingerprintOf(byScene);
            if (!fingerprint.equals(loadedFingerprint)) {
                loadedFingerprint = fingerprint;
                versionCounter++;
            }
            long version = versionCounter;
            current.set(new Snapshot(version, byScene));
            loadedAtNanos = System.nanoTime();
            if (previous == null || previous.version() != version) {
                log.info("AI 提示词库表版本已加载：{}；其余场景使用 classpath 内置提示词",
                        byScene.isEmpty() ? "（库表无启用版本）" : describe(byScene));
            }
        } catch (RuntimeException e) {
            // 与模型配置/场景绑定同一条原则：读库失败不能把 AI 打挂，退回（或沿用）内置提示词
            if (previous == null) {
                log.error("AI 提示词库表加载失败，全部场景使用 classpath 内置提示词", e);
                current.set(new Snapshot(System.nanoTime(), Map.of()));
            } else {
                log.error("AI 提示词库表加载失败，继续沿用上一份快照（版本 {}）", previous.version(), e);
            }
            loadedAtNanos = System.nanoTime();
        }
    }

    private static String describe(Map<SceneCode, Loaded> byScene) {
        StringBuilder sb = new StringBuilder();
        byScene.forEach((scene, loaded) -> {
            if (sb.length() > 0) {
                sb.append('，');
            }
            sb.append(scene.getCode()).append('=').append(loaded.versionTag());
        });
        return sb.toString();
    }

    private static String fingerprintOf(Map<SceneCode, Loaded> byScene) {
        StringBuilder sb = new StringBuilder();
        byScene.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(entry.getKey().getCode()).append('=')
                        .append(entry.getValue().rowId()).append(':')
                        .append(entry.getValue().versionTag()).append(':')
                        .append(Integer.toHexString(entry.getValue().text().hashCode())).append(';'));
        return sb.toString();
    }

    /**
     * 库表行 → 可用的提示词。
     * <p>
     * 校验不通过时返回 null 并 WARN：坏版本被跳过，该场景退回内置提示词。
     */
    private Loaded toLoaded(SceneCode scene, AiPromptTemplateEntity row) {
        String text = row.getPromptBody();
        List<String> variables = parseVariables(row.getVariablesJson(), scene);
        try {
            validateRenderable(text, variables, "scene=" + scene.getCode() + " id=" + row.getId());
        } catch (IllegalArgumentException e) {
            log.warn("库表提示词校验不通过，该场景退回 classpath 内置提示词：scene={} id={} version={} 原因={}",
                    scene.getCode(), row.getId(), row.getVersionNo(), e.getMessage());
            return null;
        }
        PromptTemplate template = PromptTemplate.builder()
                .template(text)
                .renderer(renderer)
                .build();
        return new Loaded(text, template, tagOf(scene, row.getVersionNo()), "db#" + row.getId(), row.getId(), variables);
    }

    /** 兼容历史数据：{@code variables_json} 缺失时用 yaml 声明的变量清单。 */
    private List<String> parseVariables(String variablesJson, SceneCode scene) {
        if (!StringUtils.hasText(variablesJson)) {
            return aiProperties.scene(scene).getVariables();
        }
        List<String> names = new ArrayList<>();
        Matcher matcher = JSON_STRING.matcher(variablesJson);
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
        }
        if (!names.isEmpty()) {
            return names;
        }
        // "[]" 是合法且常见的"无变量"，不能当成解析失败刷告警（否则每次启动都有一串噪音）
        if (variablesJson.replaceAll("[\\[\\]\\s,]", "").isEmpty()) {
            return names;
        }
        log.warn("场景 {} 的 variables_json 无法解析出变量名（内容={}），退用 yaml 声明：{}",
                scene.getCode(), variablesJson, aiProperties.scene(scene).getVariables());
        return aiProperties.scene(scene).getVariables();
    }

    // ------------------------------------------------------------------ classpath 内置

    private Loaded loadBuiltin(SceneCode scene) {
        AIProperties.SceneProperties config = aiProperties.scene(scene);
        String path = config.getPrompt();
        if (!StringUtils.hasText(path)) {
            throw new IllegalStateException(
                    "AI 场景未配置提示词路径：ai.scenes." + scene.getConfigKey() + ".prompt");
        }
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词文件不存在：" + path);
        }
        String text;
        try {
            text = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词文件失败：" + path, e);
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("提示词内容为空，请检查文件：" + path);
        }

        PromptTemplate template = PromptTemplate.builder()
                .template(text)
                .renderer(renderer)
                .build();
        try {
            // 按声明的变量名试渲染一次：模板里出现未声明的 {xxx} 会在这里直接失败，
            // 而不是等到线上某个请求才炸
            validateRenderable(text, config.getVariables(), "classpath:" + path);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("内置提示词模板校验失败（scene=" + scene.getCode() + "）：" + path, e);
        }

        Loaded loaded = new Loaded(text, template, tagOf(scene, config.getVersion()),
                "classpath", null, config.getVariables());
        log.info("AI 内置提示词加载完成 scene={} version={} path={}（{} 字）",
                scene.getCode(), config.getVersion(), path, text.length());
        return loaded;
    }

    /**
     * 版本标签：{@code chat@v1}。
     * <p>
     * 库表用整型 {@code version_no}、内置用 yaml 的字符串 {@code version}（{@code v1}），
     * 这里统一补上 {@code v} 前缀，避免同一列里出现 {@code chat@1} 与 {@code chat@v1} 两种写法。
     */
    private static String tagOf(SceneCode scene, Object version) {
        String raw = version == null ? "" : String.valueOf(version).trim();
        if (raw.isEmpty()) {
            raw = "0";
        }
        String tag = scene.getConfigKey() + "@" + (raw.startsWith("v") ? raw : "v" + raw);
        return tag.length() <= TAG_MAX_LENGTH ? tag : tag.substring(tag.length() - TAG_MAX_LENGTH);
    }

    /**
     * 一个场景当前生效的提示词（不可变）。
     *
     * @param rowId    库表行 id；classpath 来源时为 null
     * @param source   {@code db#<id>} 或 {@code classpath}，用于日志与排障
     * @param variables 变量清单（库表来自 {@code variables_json}，内置来自 yaml）
     */
    private record Loaded(String text, PromptTemplate template, String versionTag, String source,
                          Long rowId, List<String> variables) {
    }

    /** 不可变快照：一次请求内看到的提示词必须是同一份。 */
    private record Snapshot(long version, Map<SceneCode, Loaded> byScene) {
    }
}
