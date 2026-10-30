package com.threadfit.glucopilot.ai.scene.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;

/**
 * 后台"场景配置（场景 → 模型 / 场景参数）"的出入参。
 * <p>
 * 这组接口<b>不在 4.x 契约里</b>（契约只写了提示词 4.11~4.13），是本轮与提示词一起做的：
 * 后台改"某个场景用哪个模型、温度多少"，与改提示词是同一类需求（改完不用重新打包）。
 * 因此字段命名与 4.12 保持一致风格，权限点单列 {@code ai:scene:list} / {@code ai:scene:manage}。
 */
public class AdminAiSceneDtos {

    /** 列表出参：四个场景 + 可选模型清单（前端下拉用）。 */
    @Data
    @Builder
    public static class SceneList {

        private List<SceneRow> records;

        private List<ModelOption> models;
    }

    /**
     * 一个场景的配置。
     * <p>
     * 同时给出三组值，让"后台配了什么 / yaml 兜底是什么 / 现在真正生效的是什么"一眼可分：
     * {@code modelCode,temperature,maxTokens,thinking}（后台）→
     * {@code yaml*}（yaml 兜底）→ {@code effectiveModelCode/effectiveModelSource}（实际生效）。
     */
    @Data
    @Builder
    public static class SceneRow {

        private String sceneCode;

        private String configKey;

        private String sceneName;

        private Boolean requiresVision;

        // ---- 后台配置（DB 非空字段优先，null = 继承） ----
        private String modelCode;

        private BigDecimal temperature;

        private Integer maxTokens;

        private Boolean thinking;

        private Integer status;

        private Long configVersion;

        private String remark;

        /** 后台是否有这一行（false = 完全继承，等同引入本功能前的行为）。 */
        private Boolean configured;

        // ---- 实际生效 ----
        private String effectiveModelCode;

        /** 生效来源：{@code db} / {@code yaml} / 通道默认（由 AiModelSpec.source() 给出）。 */
        private String effectiveModelSource;

        // ---- yaml 兜底值（后台留空时用的就是这些） ----
        private String yamlModelCode;

        private BigDecimal yamlTemperature;

        private Integer yamlMaxTokens;

        private Boolean yamlThinking;

        /** model_code 是否对该场景生效：文本与视觉场景都为 true（视觉场景要求绑定的模型能力含 vision，否则回落通道视觉模型）。 */
        private Boolean modelCodeApplies;

        /** 给前端的一句说明（例如视觉场景为什么没有模型下拉）。 */
        private String hint;
    }

    /** 可选模型（只返回启用的，避免绑定到已停用模型后"配了却不生效"）。 */
    @Data
    @Builder
    public static class ModelOption {

        private String code;

        private String name;

        private Integer providerCode;

        private Boolean isDefault;

        /** 厂商中文名，前端直接展示。 */
        private String providerName;

        /**
         * 能力集合（{@code text,tool,stream} / {@code vision,stream}）。
         *
         * <p>前端据此过滤下拉：视觉场景只列能力含 vision 的模型、文本场景只列含 text 的。
         * 过滤只防误选，真正的防线是保存时的硬校验（能力不匹配直接 400）。
         */
        private String capability;

        /** 能力的中文展示（「文本 / 工具 / 流式」），避免前端再维护一份映射。 */
        private String capabilityText;
    }

    /** 保存入参：留空 = 不改该字段；{@code clear=true} = 全部改回继承。 */
    @Data
    public static class SaveRequest {

        @Size(max = 64, message = "模型编码最长 64 位")
        private String modelCode;

        @DecimalMin(value = "0.0", message = "温度不能小于 0")
        @DecimalMax(value = "2.0", message = "温度不能大于 2")
        private BigDecimal temperature;

        @Min(value = 1, message = "maxTokens 必须为正整数")
        private Integer maxTokens;

        /** 是否开启思维链（DeepSeek 专有，视觉场景忽略）。 */
        private Boolean thinking;

        @Min(value = 0, message = "status 只能是 0 或 1")
        @Max(value = 1, message = "status 只能是 0 或 1")
        private Integer status;

        @Size(max = 255, message = "备注最长 255 位")
        private String remark;

        /** true = 把该场景的所有覆盖清空，改回继承 yaml / 通道默认。 */
        private Boolean clear;

        /** 乐观锁：该场景已有配置行时必填（列表里回传）。 */
        private Long configVersion;
    }
}
