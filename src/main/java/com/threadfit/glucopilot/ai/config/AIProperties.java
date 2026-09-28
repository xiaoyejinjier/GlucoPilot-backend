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

        /** 送入模型的最近原文消息条数；与 chat_session.window_msg_count 语义一致。 */
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
}
