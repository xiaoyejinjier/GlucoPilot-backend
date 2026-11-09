package com.threadfit.glucopilot.ai.model.controller;

import com.threadfit.glucopilot.ai.model.dto.AiUsageDtos;
import com.threadfit.glucopilot.ai.model.service.AiUsageService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * AI 用量报表（只读）。
 *
 * <h2>权限</h2>
 * 用 {@code ai:usage:view}（V53 从 {@code ai:model:query} 拆出）：用量是成本视角，
 * 与"能改模型配置"（{@code ai:model:manage}）、"能看模型清单/诊断"（{@code ai:model:query}）
 * 是三类不同的关注点。这一页只含「天 × 模型」的聚合数字，不涉及任何用户对话内容。
 *
 * <h2>参数</h2>
 * 两个日期都可空：全空 → 最近 7 天；只给 {@code endDate} → 以它为终点往前推 7 天；
 * 只给 {@code startDate} → 以今天为终点。区间上限与起止顺序的校验在 Service 里。
 */
@RestController
@RequestMapping("/admin/v1/ai/usage")
@RequiredArgsConstructor
public class AdminAiUsageController {

    private final AiUsageService service;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:usage:view')")
    public ApiResponse<AiUsageDtos.Report> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(service.report(startDate, endDate));
    }
}
