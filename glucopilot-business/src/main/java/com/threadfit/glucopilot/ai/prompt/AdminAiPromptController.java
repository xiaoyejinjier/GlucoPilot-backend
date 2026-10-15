package com.threadfit.glucopilot.ai.prompt;

import com.threadfit.glucopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"提示词管理"（《接口设计文档》4.11~4.13）。
 *
 * <h2>与契约的对应关系</h2>
 * <ul>
 *   <li>4.11 {@code GET /admin/v1/ai-prompt-templates}（权限 {@code ai:prompt:list}）；</li>
 *   <li>4.12 {@code POST / PATCH /admin/v1/ai-prompt-templates[/{id}]}（{@code ai:prompt:manage}）；</li>
 *   <li>4.13 {@code PATCH /admin/v1/ai-prompt-templates/{id}/activate}（{@code ai:prompt:manage}）。</li>
 * </ul>
 * 契约标注的页面是 {@code /ai/config}，但那一页是另一个菜单（2012，其它 AI 配置），
 * 因此提示词管理落在新菜单 2020（{@code /ai/prompt}）——接口路径仍按契约，不做改动。
 *
 * <h2>为什么不加 {@code @Log}</h2>
 * 与 {@code AdminAiModelController} 一致：提示词正文会随入参整体进 {@code sys_oper_log}，
 * 而运营改提示词是高频且内容很长的操作，全量落进操作日志既没用也占空间。
 * 审计靠服务里的 {@code log.info}（含场景/版本/ID/是否启用）+ 表里的 {@code change_note}。
 */
@RestController
@RequestMapping("/admin/v1/ai-prompt-templates")
@RequiredArgsConstructor
public class AdminAiPromptController {

    private final AdminAiPromptService service;

    /** 4.11 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:prompt:list')")
    public ApiResponse<AdminAiPromptDtos.PromptList> list(
            @RequestParam(required = false) String sceneCode,
            @RequestParam(required = false) Integer status) {
        return ApiResponse.ok(service.list(sceneCode, status));
    }

    /** 4.12 新增版本（默认草稿） */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('ai:prompt:manage')")
    public ApiResponse<AdminAiPromptDtos.SaveResult> create(
            @Valid @RequestBody AdminAiPromptDtos.SaveRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    /** 4.12 编辑：草稿原地更新；已启用版本则追加新版本 */
    @PatchMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('ai:prompt:manage')")
    public ApiResponse<AdminAiPromptDtos.SaveResult> update(
            @PathVariable Long id, @Valid @RequestBody AdminAiPromptDtos.SaveRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** 4.13 启用指定版本（同场景其它版本自动停用） */
    @PatchMapping("/{id}/activate")
    @PreAuthorize("@ss.hasPermi('ai:prompt:manage')")
    public ApiResponse<Void> activate(@PathVariable Long id) {
        service.activate(id);
        return ApiResponse.ok(null);
    }
}
