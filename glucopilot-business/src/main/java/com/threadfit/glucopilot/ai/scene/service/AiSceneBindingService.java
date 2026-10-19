package com.threadfit.glucopilot.ai.scene.service;

import com.threadfit.glucopilot.ai.core.SceneCode;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 场景 → 模型/参数的绑定读取契约（契约 4.16 支撑：AI 场景列表与绑定页）。
 *
 * <h2>「继承」是一等公民</h2>
 * 绑定表的语义不是"给每个场景写死参数"，而是"<b>覆盖</b>通道默认"。因此没配置时返回
 * {@link SceneBinding#INHERIT}（全字段 null、source=inherit），调用方按"未覆盖"处理即可，
 * <b>永远不需要判空</b>——这一点是这条链路最容易写错的地方（曾经出现"配置了却没生效"）。
 *
 * <h2>优先级</h2>
 * <b>库表 {@code ai_scene_config} &gt; yaml {@code ai.scenes.*} &gt; 通道默认</b>。
 * 解析顺序在 {@code SceneModelResolver} 里落地，本接口只负责把库表那一层读出来。
 *
 * <h2>刷新</h2>
 * 与提示词一致：内容指纹 + TTL；后台保存后由实现调用 `refreshNow()` 让本实例立即生效。
 */
public interface AiSceneBindingService {

    /** 某场景的绑定；没有配置时返回 {@link SceneBinding#INHERIT}（永不返回 null）。 */
    SceneBinding binding(SceneCode scene);

    /** 当前快照（一次请求内看到的必须一致）。 */
    Snapshot snapshot();

    /** 后台保存后调用：本实例立即生效。 */
    void refreshNow();

    /**
     * 一个场景的绑定值（不可变）。
     *
     * @param modelCode   绑定的模型编码；null = 用通道默认
     * @param temperature 场景级温度；null = 继承
     * @param maxTokens   场景级输出上限；null = 继承
     * @param thinking    是否开启推理链（DeepSeek 专有）；null = 不显式设置
     * @param source      {@code db} 或 {@code inherit}
     */
    record SceneBinding(String sceneCode, String modelCode, Double temperature, Integer maxTokens,
                        Boolean thinking, String source) {

        /** 全空的绑定：表示"这个场景什么都不覆盖"。 */
        public static final SceneBinding INHERIT = new SceneBinding(null, null, null, null, null, "inherit");

        public boolean hasModel() {
            return StringUtils.hasText(modelCode);
        }

        public boolean hasParams() {
            return temperature != null || maxTokens != null || thinking != null;
        }

        /** 快照指纹用：只含"会影响行为"的字段，不含 source 这类展示字段。 */
        public String buildFingerprint() {
            return modelCode + "|" + temperature + "|" + maxTokens + "|" + thinking;
        }
    }

    /** 不可变快照：一次请求内看到的场景绑定必须是同一份。 */
    record Snapshot(long version, Map<String, SceneBinding> bySceneCode) {
    }
}
