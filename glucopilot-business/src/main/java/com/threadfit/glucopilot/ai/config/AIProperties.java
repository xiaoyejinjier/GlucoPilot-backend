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

    private PromptProperties prompt = new PromptProperties();

    private ChatProperties chat = new ChatProperties();

    private ModelProperties model = new ModelProperties();

    /**
     * 模型配置的写入门槛。
     * <p>
     * 做"保存前连通性校验"是因为一类事故只有真实调用才暴露：模型名写错、密钥属于别的账号、
     * baseUrl 少写 {@code /v1}——这些在后台看起来完全正常，直到用户提问才 404/401。
     * 校验失败必须让保存失败，否则"填错了也能存"等于没有校验。
     */
    @Data
    public static class ModelProperties {

        /**
         * 保存模型配置前是否强制发一次探针请求。
         * <p>
         * 默认开启。关闭只应出现在两种场合：CI/本地没有外网，或者新接入的厂商暂时连不通但需要先把
         * 配置录进来。关闭时每行的 {@code verify_skipped_reason} 会写明"服务端关闭了校验"，
         * 不至于事后无从追查。
         */
        private boolean verifyOnSave = true;
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

        /**
         * 采样温度；留空继承全局配置
         */
        private Double temperature;

        /** 最大输出 token；留空继承全局配置 */
        private Integer maxTokens;

        /** 是否开启思维链；留空继承全局配置（当前对话场景全局关闭） */
        private Boolean thinking;
    }

    /**
     * 会话记忆（② 期：token 预算窗口 + 滚动摘要）。
     * <p>
     * <b>为什么从"条数"改成"token 预算"</b>：实测一轮几乎空的对话（系统提示词 1651 字 + 6 个工具定义）
     * 就已经是 5012 prompt tokens，而单条助手回复上限 20 000 字、回放时不做逐条截断——
     * 也就是"20 条"能装下的内容量可以差两个数量级。预算必须按 token 算。
     */
    @Data
    public static class MemoryProperties {

        /**
         * 历史窗口的<b>条数</b>兜底（默认 20）。
         * <p>
         * 仍然保留它，但降级为"数量上限"而不是主判据：主判据是 {@link #maxPromptTokens}。
         * 两者取小——这样即使估算器被某条极短消息带偏，也不会一次塞进几百条。
         */
        private int windowMessages = 20;

        /** 仓库一次最多捞多少条可回放消息（防御性上限，防配置被改成大数后拉全表）。 */
        private int maxFetch = 200;

        /**
         * 一轮 prompt 允许的总 token（默认 4096，含系统提示词与工具定义）。
         * <p>
         * 保守取值的理由：固定开销实测约 5k（系统提示词 + 工具定义随工具数增长），
         * 4096 是一个"安全阀"而不是"目标值"；调大只会让摘要更晚触发、单轮更贵。
         */
        private int maxPromptTokens = 4096;

        /**
         * 无论预算多紧，都至少保留最近这么多条历史消息。
         * <p>
         * 这是"宁可超预算也要保住连贯性"的兜底：只留最后一轮会让多轮追问（"那第二条呢"）直接断片。
         */
        private int keepRecentMessages = 6;

        /** 滚动摘要的长度上限（字符）。超长摘要本身也会吃预算。 */
        private int summaryMaxChars = 800;

        /**
         * 摘要场景的单次输入上限（字符）：要压缩的原文超过它时只取最近这么多字。
         * <p>
         * 从 6000 降到 3000 是实测结果：6000 字的摘要在该通道上会超过读超时（ReadTimeoutException），
         * 任务失败 → 摘要一直建不起来。输入越长首 token 越慢，而摘要对"少一点原文"并不敏感。
         */
        private int summaryInputMaxChars = 3000;

        /** 摘要输出的 token 上限：摘要正文只有几百字，封顶可杜绝"模型长篇发挥"拖长生成时间。 */
        private int summaryMaxTokens = 600;

        /** 是否把最近的工具结果摘要注入上下文（B3 的后半）。 */
        private boolean toolReplayEnabled = true;

        /** 回放的最近工具调用条数上限。 */
        private int toolReplayMaxItems = 3;

        /** 工具结果摘要（chat_tool_call.result_digest）的字符上限。 */
        private int toolDigestMaxChars = 512;

        // ---------------- ③ 期：长期记忆 ----------------

        /** 注入上下文的最多记忆条数（按置信度降序取）；0 = 不注入。 */
        private int injectMaxItems = 8;

        /** 抽取时最多回看多少条消息（长期记忆只要稳定事实，不需要整段历史）。 */
        private int memoryExtractMaxMessages = 60;

        /** 抽取输入的字符上限（超长只取最近的部分）。 */
        private int memoryExtractInputMaxChars = 3000;

        /** 抽取输出的 token 上限（只输出 JSON）。 */
        private int memoryExtractMaxTokens = 500;
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
