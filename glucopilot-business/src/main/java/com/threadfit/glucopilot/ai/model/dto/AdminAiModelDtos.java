package com.threadfit.glucopilot.ai.model.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台"AI 模型配置"的入参/出参（接口 4.x，路径 {@code /admin/v1/ai/models}）。
 * <p>
 * <b>安全约定（本类最重要的事）</b>：出参<b>永远不含明文密钥</b>，只有 {@code apiKeyMasked}
 * （形如 {@code sk-****klmn}）；入参的 {@code apiKey} 留空表示"不修改已保存的密钥"，
 * 避免前端把掩码回填进表单再写回库——这是这类页面最常见的自伤。
 */
public final class AdminAiModelDtos {

    private AdminAiModelDtos() {
    }

    /** 列表项 / 详情。 */
    @Data
    @Builder
    public static class ModelRow {

        /** 主键（Long 全局序列化为字符串，前端按 string 处理）。 */
        private String id;

        private String modelCode;

        private String modelName;

        private Integer providerCode;

        /** 厂商展示文案，前端直接用，避免前端再维护一份映射。 */
        private String providerText;

        /** 能力集合，逗号分隔：{@code text} / {@code vision} / {@code tool} / {@code stream}。 */
        private String capability;

        /** 能力展示文案（「文本 / 流式」），前端直接显示。 */
        private String capabilityText;

        private String apiBaseUrl;

        private String modelEndpoint;

        private BigDecimal temperature;

        private BigDecimal topP;

        private Integer maxTokens;

        private Boolean thinking;

        private Integer timeoutMs;

        private Integer isDefault;

        private Integer isEnabled;

        /** 掩码后的密钥，如 {@code sk-****klmn}；无密钥时为空串。 */
        private String apiKeyMasked;

        /** 密钥来源：{@code db}=后台单独配过；{@code yaml}=沿用环境变量/yaml；{@code none}=两边都没有。 */
        private String apiKeySource;

        /** 乐观锁版本，修改时必须回传。 */
        private Integer configVersion;

        /** 这条配置当前是否真的在生效：{@code db}=已启用并参与运行时解析；{@code yaml}=未启用，实际用的是回落值。 */
        private String source;

        /**
         * 事实上的角色：{@code text-default}=当前文本对话实际在用的模型；{@code vision}=当前视觉实际在用的模型；
         * null=启用了但没在供能（或有默认标记却没被选中）。
         * <p>
         * 与 {@code isDefault}/{@code isEnabled}（意愿）分开，是因为"意愿都对、事实上却没生效"
         * 正是最难自查的一类问题。
         */
        private String effectiveRole;

        private LocalDateTime lastTestAt;

        private Boolean lastTestOk;

        private String lastTestMessage;

        private String remark;

        private LocalDateTime updatedAt;
    }

    /** 列表响应（外层再包 ApiResponse）。 */
    @Data
    @Builder
    public static class ModelList {
        private List<ModelRow> records;

        /** 当前文本对话实际使用的模型编码（不是"标了默认"的那个，而是真正生效的那个）。 */
        private String textDefaultCode;

        /** 该默认值的来源：{@code db} 或 {@code yaml}。 */
        private String textDefaultSource;

        /** 默认值是怎么选出来的：默认标记 / 唯一启用的文本模型 / 最近修改的文本模型 / yaml 兜底。 */
        private String textDefaultReason;
    }

    /** 新增/修改入参。 */
    @Data
    public static class SaveRequest {

        /** 新增必填；修改时忽略（模型编码是引用键，被场景/代码引用后不应改名）。 */
        @Size(max = 64, message = "模型编码最长 64 字符")
        private String modelCode;

        @NotBlank(message = "模型名称不能为空")
        @Size(max = 64, message = "模型名称最长 64 字符")
        private String modelName;

        @NotNull(message = "厂商不能为空")
        @Min(value = 1, message = "厂商取值 1-5")
        private Integer providerCode;

        /**
         * 能力集合：逗号分隔的 {@code text} / {@code vision} / {@code tool} / {@code stream}。
         * <p>
         * 留空按「只有 text」处理，兼容尚未升级的前端；它决定这条配置参与哪个默认池的判定，
         * 以及未自带密钥时沿用哪一套 yaml 凭据。
         * <p>
         * 这里只用长度约束，取值校验放在 service 里（{@code AiModelCapability.parseStrict}）：
         * 需要给出"只能是 text/vision/tool/stream"这种带合法取值的提示，注解的 message 拼不出可变集合。
         */
        @Size(max = 64, message = "能力取值过长")
        private String capability;

        @Size(max = 512, message = "baseUrl 过长")
        private String apiBaseUrl;

        @NotBlank(message = "模型名（modelEndpoint）不能为空")
        @Size(max = 128, message = "模型名过长")
        private String modelEndpoint;

        @DecimalMin(value = "0.0", message = "温度不能小于 0")
        @DecimalMax(value = "2.0", message = "温度不能大于 2")
        private BigDecimal temperature;

        @DecimalMin(value = "0.0", message = "topP 不能小于 0")
        @DecimalMax(value = "1.0", message = "topP 不能大于 1")
        private BigDecimal topP;

        @Min(value = 1, message = "maxTokens 必须为正整数")
        private Integer maxTokens;

        /** 仅 DeepSeek 有意义；null = 不显式设置。 */
        private Boolean thinking;

        @Min(value = 1, message = "超时必须为正整数（毫秒）")
        private Integer timeoutMs;

        /** 明文密钥；<b>留空表示不修改</b>。 */
        @Size(max = 512, message = "密钥过长")
        private String apiKey;

        /** true = 显式清空密钥，回到"沿用环境变量/yaml"。 */
        private Boolean clearApiKey;

        @NotNull(message = "启用状态不能为空")
        private Integer isEnabled;

        @NotNull(message = "默认标记不能为空")
        private Integer isDefault;

        @Size(max = 255, message = "备注过长")
        private String remark;

        /** 修改时必填（乐观锁）。 */
        private Integer configVersion;
    }

    /** 启停入参。 */
    @Data
    public static class EnabledRequest {
        @NotNull(message = "enabled 不能为空")
        private Integer enabled;
    }

    /** 连通性测试结果。 */
    @Data
    @Builder
    public static class TestResult {
        private boolean ok;
        /** OK / AUTH / MODEL_NOT_FOUND / RATE_LIMIT / TIMEOUT / NETWORK / UNKNOWN。 */
        private String category;
        /** 给管理员看的一句话建议（"该改哪个字段"）。 */
        private String advice;
        private long elapsedMs;
        /** 上游回显的模型名（有些网关会替换模型，回显是唯一能发现的方式）。 */
        private String modelEcho;
        /** 原始消息摘要（已截断、不含密钥）。 */
        private String message;
    }
}
