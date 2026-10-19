package com.threadfit.glucopilot.ai.model.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.threadfit.glucopilot.ai.model.dto.AdminAiModelDtos;
import com.threadfit.glucopilot.ai.model.service.AdminAiModelService;

/**
 * 后台"AI 模型配置"（P0 只读 + P1 可写）。
 *
 * <h2>为什么需要它</h2>
 * 在此之前，模型名 / 密钥 / 参数都写在 yaml 里，改一次要改文件 + 重新打包 + 重启。
 * 2026-10-12 实测吃过两次这个亏：视觉模型名写成账号下不存在的 {@code qwen3-vl-flash}
 * （用户发图才报 {@code 404 Model not exist}），以及配置被打进 jar 导致"改了源文件不重新构建不生效"。
 *
 * <h2>权限与安全</h2>
 * <ul>
 *   <li>读写分离：列表/详情要 {@code ai:model:query}，任何写操作与连通性测试要 {@code ai:model:manage}；</li>
 *   <li>出参永远只有掩码密钥（见 {@link AdminAiModelDtos}）；入参"留空=不改"；</li>
 *   <li>写接口批量走 {@link AdminAiModelService} 的乐观锁，避免两个管理员互相覆盖；</li>
 *   <li>RuoYi 的操作日志会记录调用者与入参，而 <b>{@code apiKey} 是明文入参</b>——
 *       因此这里刻意<b>不</b>加 {@code @Log} 注解，避免密钥被写进 {@code sys_oper_log}；
 *       审计信息由 {@link AdminAiModelService} 的日志给出（含 modelCode 与"密钥是否更新"，不含密钥本身）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/v1/ai/models")
@RequiredArgsConstructor
public class AdminAiModelController {

    private final AdminAiModelService service;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:model:query')")
    public ApiResponse<AdminAiModelDtos.ModelList> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('ai:model:query')")
    public ApiResponse<AdminAiModelDtos.ModelRow> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('ai:model:manage')")
    public ApiResponse<AdminAiModelDtos.ModelRow> create(
            @Valid @RequestBody AdminAiModelDtos.SaveRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('ai:model:manage')")
    public ApiResponse<AdminAiModelDtos.ModelRow> update(
            @PathVariable Long id, @Valid @RequestBody AdminAiModelDtos.SaveRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/enabled")
    @PreAuthorize("@ss.hasPermi('ai:model:manage')")
    public ApiResponse<AdminAiModelDtos.ModelRow> setEnabled(
            @PathVariable Long id, @Valid @RequestBody AdminAiModelDtos.EnabledRequest request) {
        return ApiResponse.ok(service.setEnabled(id, request.getEnabled()));
    }

    @PostMapping("/{id}/default")
    @PreAuthorize("@ss.hasPermi('ai:model:manage')")
    public ApiResponse<AdminAiModelDtos.ModelRow> setDefault(@PathVariable Long id) {
        return ApiResponse.ok(service.setDefault(id));
    }

    /** 连通性测试：真的发一次最小请求，把"配错了"提前到点击时（并按错误类型给出该改哪个字段）。 */
    @PostMapping("/{id}/test")
    @PreAuthorize("@ss.hasPermi('ai:model:manage')")
    public ApiResponse<AdminAiModelDtos.TestResult> test(@PathVariable Long id) {
        return ApiResponse.ok(service.test(id));
    }
}
