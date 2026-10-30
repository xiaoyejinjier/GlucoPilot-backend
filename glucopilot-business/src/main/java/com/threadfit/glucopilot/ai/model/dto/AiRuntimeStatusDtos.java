package com.threadfit.glucopilot.ai.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运行时配置诊断（{@code GET /admin/v1/ai/runtime/status}）。
 *
 * <h2>为什么需要它</h2>
 * 「后台配了 A、实际调的是 B」这类问题此前只能靠翻启动日志 + 看代码推断：
 * 快照日志只在<b>首次加载</b>时打，实例里缓存过就再也看不到；而运营想知道
 * 「现在这一刻，某个场景到底会用哪个模型、为什么是它」，没有任何入口。
 * 这个接口把快照 + 场景绑定合成一份"此刻的事实"，并把可疑配置列成 {@code issues}。
 *
 * <h2>读的顺序</h2>
 * 每个场景的 {@code source} 只有三种取值，与代码里的三条链一一对应：
 * <ul>
 *   <li>{@code binding}：后台 {@code ai_scene_config.model_code} 生效（能解析、能力匹配）；</li>
 *   <li>{@code default}：没绑定或绑定不可用，走了通道默认（DB 的默认标记/唯一启用者）；</li>
 *   <li>{@code fallback}：DB 里连默认都没有，落到 yaml 通道兜底。</li>
 * </ul>
 * 看到 {@code fallback} 就该去「模型配置」补一个默认，否则改 yaml 才能换模型。
 */
public final class AiRuntimeStatusDtos {

    private AiRuntimeStatusDtos() {
    }

    @Data
    @Builder
    public static class Status {
        /** 快照版本：内容指纹变化才 +1，用它判断"这次刷新到底有没有变"。 */
        private Long version;
        /** 当前快照里 DB 行数（可被解析的），与 skipped 相加为启用行总数。 */
        private Integer dbModelCount;
        /** 因密钥解不开/字段非法被跳过的行数。 */
        private Integer skipped;
        private ModelBrief textDefault;
        private ModelBrief visionDefault;
        private List<SceneStatus> scenes;
        /** 配置层面的可疑点（不是异常，是"大概率不符合预期"）。 */
        private List<String> issues;
        private LocalDateTime sampledAt;
    }

    @Data
    @Builder
    public static class ModelBrief {
        private String modelCode;
        private String modelEndpoint;
        /** db / yaml */
        private String source;
        /** 判据文案（默认标记 / 唯一启用 / 最近修改 / yaml 兜底）。 */
        private String reason;
        private String capability;
    }

    @Data
    @Builder
    public static class SceneStatus {
        private String sceneCode;
        private String sceneName;
        private Boolean requiresVision;
        /** 最终会落库/发给厂商的那个模型编码。 */
        private String modelCode;
        private String modelEndpoint;
        /** binding / default / fallback */
        private String source;
        /** 后台绑定的编码（可能不可用，这时 source 会是 default/fallback）。 */
        private String boundModelCode;
        private Boolean bindingUsable;
    }
}
