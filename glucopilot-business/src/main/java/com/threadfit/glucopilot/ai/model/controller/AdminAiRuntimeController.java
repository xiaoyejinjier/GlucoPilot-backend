package com.threadfit.glucopilot.ai.model.controller;

import com.threadfit.glucopilot.ai.model.dto.AiRuntimeStatusDtos;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeStatusService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时配置诊断：此刻每个场景会用哪个模型、为什么是它、有哪些可疑配置。
 *
 * <h2>为什么单开一个控制器</h2>
 * 它既不属于「模型配置」（改数据），也不属于「场景配置」（改绑定），而是把两者合成一份
 * <b>只读的运行期事实</b>。放在任一 CRUD 控制器下都会让权限语义变模糊（看诊断要 manage 还是 query？），
 * 这里统一用 {@code ai:model:query}：能看模型清单的人就能看"现在实际在用哪个"。
 */
@RestController
@RequestMapping("/admin/v1/ai/runtime")
@RequiredArgsConstructor
public class AdminAiRuntimeController {

    private final AiRuntimeStatusService service;

    @GetMapping("/status")
    @PreAuthorize("@ss.hasPermi('ai:model:query')")
    public ApiResponse<AiRuntimeStatusDtos.Status> status() {
        return ApiResponse.ok(service.status());
    }
}
