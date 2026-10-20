package com.threadfit.glucopilot.ai.scene.service;

import com.threadfit.glucopilot.ai.scene.dto.AdminAiSceneDtos;

/**
 * 后台场景管理契约（4.16：场景列表 + 场景级模型/参数绑定）。
 *
 * <h2>列表为什么要带"实际生效值"</h2>
 * 运营在页面上看到的 {@code model_code} 只是"这个场景配了什么"，而模型真正用的是
 * 库表绑定 &gt; yaml &gt; 通道默认三者叠出来的结果。两者不一致时，只显示配置值会让运营
 * 以为"改了没生效"——因此列表同时给出 {@code effectiveModelCode} / {@code effectiveModelSource}
 * 与 {@code modelCodeApplies}，把"配了但不生效"如实告诉人。
 *
 * <h2>清空 = 改回继承</h2>
 * 保存时全空表示"什么都不覆盖"，等价于删除该场景的绑定行；这比"留一行全 null"更不容易误读。
 */
public interface AdminAiSceneService {

    /** 场景列表（含可选模型清单与实际生效值）。 */
    AdminAiSceneDtos.SceneList list();

    /**
     * 保存场景级绑定（乐观锁）；全空表示改回继承。
     *
     * @param sceneCode 场景编码（未知编码返回 400）
     */
    AdminAiSceneDtos.SceneRow save(String sceneCode, AdminAiSceneDtos.SaveRequest request);
}
