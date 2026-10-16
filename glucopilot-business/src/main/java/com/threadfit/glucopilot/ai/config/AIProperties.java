package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.core.SceneCode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模块配置（前缀 {@code ai}）。
 * <p>
 * 与 Spring AI 自身配置的分工：
 * <ul>
 *   <li>{@code spring.ai.*}（含 {@code spring.ai.deepseek.*} / {@code spring.ai.retry.*} /
 *       {@code spring.ai.tools.*}）由框架绑定，负责「provider 与框架行为」；</li>
 *   <li>{@code ai.*}（本类）负责「本项目的业务编排」：场景 → 提示词/参数、记忆窗口、
 *       医疗安全词、工具白名单。</li>
 * </ul>
 * 场景里的 {@code model-code} / {@code temperature} / {@code max-tokens} / {@code thinking}
 * 都是**可选覆盖**：不写就继承 {@code spring.ai.deepseek.chat.*} 的全局默认，
 * 避免同一份参数在两处维护。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AIProperties {

    /** 场景编排：key 为 {@link SceneCode#getConfigKey()}。 */
    private Map<String, SceneProperties> scenes = new LinkedHashMap<>();

    private MemoryProperties memory = new MemoryProperties();

    private GuardProperties guard = new GuardProperties();

    private ToolProperties tools = new ToolProperties();

    private VisionProperties vision = new VisionProperties();

    private MealProperties meal = new MealProperties();

    private PromptProperties prompt = new PromptProperties();

    private ChatProperties chat = new ChatProperties();

    @Data
    public static class MealProperties {
        private String model = "qwen3-vl-plus";
        private int maxTokens = 2048;
    }

    /**
     * 会话生命周期参数（接口 5.3 列表 / 5.4 删除 / 2.23 新建）。
     */
    @Data
    public static class ChatProperties {

        /**
         * 惰性归档阈值（天）：拉会话列表时，把"进行中但超过这么多天没有新消息"的会话置为 0 归档。
         * <p>
         * 为什么用惰性而不是定时任务：归档只影响列表展示，以及"下次进来是否接着上次那条会话说"，
         * 不值得为它引入 quartz 任务的部署、多实例去重与失败重试成本。设为 0 表示关闭归档。
         */
        private int archiveIdleDays = 30;

        /** 会话列表每页条数上限（防止前端传 pageSize=100000 把库拖垮）。 */
        private int maxPageSize = 50;
    }

    /**
     * 提示词后台化（接口 4.11~4.13）的校验参数。
     * <p>
     * 提示词从"跟着代码走"变成"运营随手可改"之后，风险从"改不动"变成"改坏了"。
     * 这里的三条是<b>阻断式</b>校验（校验不过不允许入库），默认值刻意保守：
     * 宁可让运营多点一次"保存并启用"，也不要让半截提示词上线。
     */
    @Data
    public static class PromptProperties {

        /** 正文长度下限（字符）：挡住误清空、误粘贴成一句话。 */
        private int minLength = 50;

        /** 需要做安全关键词校验的场景（{@link SceneCode#getConfigKey()}），默认只有对话场景。 */
        private List<String> safetyScenes = new ArrayList<>(List.of("chat"));

        /** 必含安全关键词（命中任意一个即可），仅对 {@link #safetyScenes} 生效。 */
        private List<String> safetyKeywords =
                new ArrayList<>(List.of("就医", "医院", "医生", "诊断"));
    }

    /** 取场景配置；未配置视为部署错误（提示词/参数没有来源，跑了也是错的）。 */
    public SceneProperties scene(SceneCode sceneCode) {
        SceneProperties scene = scenes.get(sceneCode.getConfigKey());
        if (scene == null) {
            throw new IllegalStateException("未配置 AI 场景：ai.scenes." + sceneCode.getConfigKey());
        }
        return scene;
    }

    /** 单个场景的编排参数。 */
    @Data
    public static class SceneProperties {

        /** classpath 提示词路径，如 prompts/chat/system.md */
        private String prompt;

        /** 提示词版本号，落 chat_session.system_prompt_ver，便于回溯「这条回复用的是哪版提示词」。 */
        private String version = "v1";

        /** 模板变量名清单：启动时按此清单试渲染一次，提前暴露模板里的花括号错误。 */
        private List<String> variables = new ArrayList<>();

        /** 模型编码；留空继承 spring.ai.deepseek.chat.model */
        private String modelCode;

        /** 采样温度；留空继承全局配置 */
        private Double temperature;

        /** 最大输出 token；留空继承全局配置 */
        private Integer maxTokens;

        /** 是否开启思维链；留空继承全局配置（当前对话场景全局关闭） */
        private Boolean thinking;
    }

    /** 会话记忆（chat_message 窗口）。 */
    @Data
    public static class MemoryProperties {

        /**
         * 送入模型的最近原文消息条数（窗口上限）。
         * <p>
         * 由 {@code ChatMemoryReadAdvisor.keepLastWindow(...)} 在读取侧施加——<b>不要</b>指望
         * {@code MessageWindowChatMemory.maxMessages}：框架的 {@code get()} 不裁剪，裁剪只在
         * {@code add()}（本项目不走的写入路径）上（详见该 Advisor 的类注释）。
         * <p>
         * {@code chat_session.window_msg_count} 在建会话时落的是本值的快照，仅供追溯；
         * 改这里不会回改历史会话的那一列。
         */
        private int windowMessages = 20;

        /** 单次从库里捞取的防御性上限，避免窗口配置被误改成大数后一次拉全表。 */
        private int maxFetch = 200;
    }

    /**
     * 医疗安全护栏（硬约束）。
     * <p>
     * 提示词里的「禁止给剂量、必须引导就医」是软约束（模型可能不遵守）；
     * 这里是 {@code SafeGuardAdvisor} 的硬拦截：命中敏感词的请求**不会发给模型**，
     * 直接返回固定话术，保证合规底线不依赖模型发挥。
     * <p>
     * 敏感词请保持「聚焦」（如剂量、加减药、停药），不要放单字「药」这类词，
     * 否则会把「二甲双胍能吃吗」这类正常科普问题也拦掉。
     */
    @Data
    public static class GuardProperties {

        private boolean enabled = true;

        private List<String> sensitiveWords = new ArrayList<>();

        /** 命中后的固定回复；留空则用代码内置的兜底话术。 */
        private String failureResponse;
    }

    /**
     * 工具（Function Calling）白名单。
     * <p>
     * 只注册**显式登记**的工具，绝不做包扫描：提示词注入导致越权工具调用是已知风险，
     * 白名单是这一层的最后一道闸。调用次数上限由框架配置
     * {@code spring.ai.tools.limits.*} 控制（默认超限即抛异常）。
     */
    @Data
    public static class ToolProperties {

        /** 允许送给模型的工具名（{@code ToolDefinition.name()}）；为空表示不注册任何工具。 */
        private List<String> allowList = new ArrayList<>();
    }

    /**
     * 视觉（多模态）provider 配置：阿里云百炼 qwen-vl，走 OpenAI 兼容协议。
     * <p>
     * <b>为什么放在 {@code ai.*} 而不是 {@code spring.ai.openai.*}</b>：本模块刻意不引
     * {@code spring-ai-starter-model-openai}（理由见 pom），因此
     * {@code spring-ai-autoconfigure-model-openai} 不在类路径上，没有任何自动装配会去读
     * {@code spring.ai.openai.*} —— 写在那里会变成一份「看起来生效、实则无人消费」的配置。
     * 放在这里，由 {@code AiVisionClientConfig} 显式读取并构造客户端，来源一眼可查。
     * <p>
     * <b>为什么视觉参数不放进 {@code ai.scenes.*}</b>：{@code SceneProperties} 里的
     * model-code/temperature/max-tokens 是「同一个文本 provider 的场景级覆盖」，
     * 而视觉是**另一个 provider、另一条调用链**（两阶段里的阶段 1）。两者语义不同，
     * 混在一起会让 {@code SceneModelResolver} 不得不同时理解两套 options 类型。
     */
    @Data
    public static class VisionProperties {

        /**
         * 是否装配视觉客户端。关闭时视觉场景继续抛业务异常，
         * <b>绝不回落到文本模型</b>——让文本模型「假装」看懂了图片，比明确报错危险得多。
         */
        private boolean enabled = false;

        /** OpenAI 兼容端点。百炼为 https://dashscope.aliyuncs.com/compatible-mode/v1 */
        private String baseUrl;

        /** 凭据；只放 application-local.yaml 或环境变量，禁止入库。 */
        private String apiKey;

        /** 视觉模型编码，如 qwen3-vl-plus。 */
        private String model;

        /** 图片描述的输出上限：描述要短，限死以避免视觉模型长篇发挥。 */
        private Integer maxTokens = 512;

        /** 采样温度。图片理解要稳定复现，默认取低。 */
        private Double temperature = 0.2;

        /** 单张图片的原始字节上限；超出直接拒绝，避免把超大图塞进 base64 请求。 */
        private long maxImageBytes = 5L * 1024 * 1024;

        /** 视觉调用超时（秒）：非流式调用必须有自己的超时，否则会拖住整轮 SSE。 */
        private int timeoutSeconds = 60;
    }
}
