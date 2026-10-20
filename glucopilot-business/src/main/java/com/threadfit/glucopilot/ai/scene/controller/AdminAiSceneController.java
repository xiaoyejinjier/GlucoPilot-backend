package com.threadfit.glucopilot.ai.scene.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.threadfit.glucopilot.ai.scene.dto.AdminAiSceneDtos;
import com.threadfit.glucopilot.ai.scene.service.AdminAiSceneService;

/**
 * 后台"场景配置"：把「场景 → 模型 / 场景参数」搬进后台（与提示词管理同批交付）。
 *
 * <h2>为什么放在这里而不是扩 {@code /ai-prompt-templates}</h2>
 * 两者是正交的两件事：提示词是"模型看到的内容"，场景绑定是"用哪个模型、用什么采样参数"。
 * 合在一个接口里会导致"只想换模型却必须新增一个提示词版本"这种别扭的契约（V1 的
 * {@code ai_prompt_template.model_code} 就是这个形态，本接口不再写它）。
 *
 * <h2>权限</h2>
 * 读 {@code ai:scene:list}，写 {@code ai:scene:manage}（V18 菜单 2021 / 2024）。
 * 与模型配置（{@code ai:model:manage}）分开：能改模型凭据的人不一定该改业务场景的采样参数，反之亦然。
 */
@RestController
@RequestMapping("/admin/v1/ai-scenes")
@RequiredArgsConstructor
public class AdminAiSceneController {

    private final AdminAiSceneService service;

    /** 场景列表：后台绑定值 + yaml 兜底值 + 实际生效模型（前端一屏看清"为什么是这个模型"）。 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:scene:list')")
    public ApiResponse<AdminAiSceneDtos.SceneList> list() {
        return ApiResponse.ok(service.list());
    }

    /**
     * 保存某个场景的绑定与参数。
     * <p>
     * 用 PUT 而不是 PATCH：语义是"这一份配置就是该场景的全部覆盖"，留空的字段表示"继承"
     * （配合 {@code clear=true} 可以一键清空全部覆盖），而不是"没传就不改"。
     */
    @PutMapping("/{sceneCode}")
    @PreAuthorize("@ss.hasPermi('ai:scene:manage')")
    public ApiResponse<AdminAiSceneDtos.SceneRow> save(
            @PathVariable String sceneCode, @Valid @RequestBody AdminAiSceneDtos.SaveRequest request) {
        return ApiResponse.ok(service.save(sceneCode, request));
    }
}
