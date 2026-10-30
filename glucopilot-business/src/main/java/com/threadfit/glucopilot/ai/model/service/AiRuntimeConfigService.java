package com.threadfit.glucopilot.ai.model.service;

import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.core.SceneCode;

import java.util.Map;
import com.threadfit.glucopilot.ai.model.runtime.ChatModelFactory;
import com.threadfit.glucopilot.ai.model.runtime.DynamicChatModel;

/**
 * 运行时模型配置契约：一次请求该用哪份模型凭据/参数。
 *
 * <h2>它解决的是"换了默认模型仍走老模型"</h2>
 * 模型配置在后台可改（{@code ai_model_config}），而 ChatClient 是启动期装配的。
 * 本接口提供的是<b>一份可在请求期读取的快照</b>：后台保存后 {@link #refreshNow()} 让本实例立即生效，
 * 其它实例最多滞后 TTL。装配层（{@code ChatModelFactory}/{@code DynamicChatModel}）按它决定实际调用谁。
 *
 * <h2>为什么快照里要区分"通道默认"和"某个编码"</h2>
 * 文本通道的唯一正确入口是 {@link Snapshot#resolveTextDefault()}：
 * 它回答"现在没有指定模型时该用哪份配置"（后台默认行 → 唯一/最近启用的行 → yaml）。
 * {@link Snapshot#resolveText(String)} 只回答"这个编码对应哪份配置"，
 * 拿 yaml 的模型名去调用它，会在后台把那个模型停用后悄悄退回 yaml。
 */
public interface AiRuntimeConfigService {

    /** 当前快照（读是纯内存操作）。 */
    Snapshot snapshot();

    /** 后台保存后调用：本实例立即生效。 */
    void refreshNow();

    /** 某场景应使用的文本模型规格。 */
    AiModelSpec textSpec(SceneCode scene);

    /** 视觉通道的模型规格（视觉模型由通道统一解析，不按场景配置）。 */
    AiModelSpec visionSpec();

    /** 按模型编码取规格；编码为空/未登记时回落 yaml 文本规格。 */
    AiModelSpec specByModelCode(String modelCode);

    /** 按模型名取规格；未登记返回 null。 */
    AiModelSpec specByModelName(String modelName);

    /**
     * 模型配置快照（不可变）。
     *
     * @param version             内容指纹版本（变了说明配置有改动）
     * @param byCode              模型编码 → 规格
     * @param byName              模型名（endpoint）→ 规格
     * @param yamlText            yaml 里的文本通道规格（兜底）
     * @param yamlVision          yaml 里的视觉通道规格（兜底）
     * @param textDefault         文本通道的"默认"规格；null = 没有后台默认
     * @param textDefaultReason   默认是怎么选出来的（排障用）
     * @param visionDefault       视觉通道的"默认"规格（后台 VISION 行）；null = 没有后台视觉默认
     * @param visionDefaultReason 视觉默认的判据（排障用）
     * @param skipped             读取时跳过的坏行数（解密失败/字段缺失）
     */
    record Snapshot(long version,
                    Map<String, AiModelSpec> byCode,
                    Map<String, AiModelSpec> byName,
                    AiModelSpec yamlText,
                    AiModelSpec yamlVision,
                    AiModelSpec textDefault,
                    String textDefaultReason,
                    AiModelSpec visionDefault,
                    String visionDefaultReason,
                    int skipped) {

        /**
         * 文本通道默认规格（后台默认行 → 唯一/最近启用的行 → yaml），<b>永不为 null</b>。
         * <p>
         * 这是文本通道唯一该用的解析入口，理由见 {@link AiRuntimeConfigService} 类注释。
         */
        public AiModelSpec resolveTextDefault() {
            return textDefault == null ? yamlText : textDefault;
        }

        public String textDefaultReason() {
            return textDefaultReason == null ? "yaml 兜底" : textDefaultReason;
        }

        /** 文本默认是否来自后台（而不是 yaml 兜底）。 */
        public boolean textDefaultFromDb() {
            return textDefault != null && "db".equals(textDefault.source());
        }

        /**
         * 按模型编码解析（场景绑定等显式指定编码的场景）。code 为空或查不到时回落 yaml 文本规格。
         * <p>
         * 注意"查不到"不是错误：场景可以指向一个既不在库、也不是 yaml 默认的模型名，
         * 此时仍用 yaml 的<b>凭据</b>，模型名由请求 options 决定。
         */
        public AiModelSpec resolveText(String modelCode) {
            if (modelCode != null && !modelCode.isBlank()) {
                AiModelSpec spec = byCode.get(modelCode);
                if (spec != null) {
                    return spec;
                }
            }
            return yamlText;
        }

        /**
         * 视觉通道解析：后台能力含 {@code vision} 的默认行 → 唯一/最近启用的视觉行 → yaml。
         * <p>
         * 批次 2 之前这里按 {@code model_endpoint} 反查，等于"只有和 yaml 同名的那一个视觉模型能被后台接管"；
         * 现在按<b>用途</b>判定，后台新增的任意视觉模型都能成为视觉默认（这才是"自由配置视觉模型"）。
         * <p>
         * 与文本通道同理：<b>DB 里明明有启用的视觉行，就不该悄悄退回 yaml</b>。
         */
        public AiModelSpec resolveVisionDefault() {
            return visionDefault == null ? yamlVision : visionDefault;
        }

        public String visionDefaultReason() {
            return visionDefaultReason == null ? "yaml 兜底" : visionDefaultReason;
        }

        /** 视觉默认是否来自后台（而不是 yaml 兜底）。 */
        public boolean visionDefaultFromDb() {
            return visionDefault != null && "db".equals(visionDefault.source());
        }

        public AiModelSpec resolveByName(String modelName) {
            return byName.get(modelName);
        }

        public int dbModelCount() {
            return (int) byCode.values().stream().filter(s -> "db".equals(s.source())).count();
        }
    }
}
