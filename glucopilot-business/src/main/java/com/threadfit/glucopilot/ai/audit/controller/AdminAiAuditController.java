package com.threadfit.glucopilot.ai.audit.controller;

import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.ai.audit.service.AiAuditService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 审计：工具调用 / 长期记忆 / 滚动摘要 / 多模态任务。
 *
 * <h2>权限</h2>
 * 全部 {@code ai:chat:audit}——与会话审计同一个点。理由：这四张表要么直接由对话产生
 * （工具调用、摘要），要么决定下一次对话的输入（长期记忆），要么是用户上传的图片
 * （多模态任务）；能看会话原文的人才该看到它们，不值得再拆四个权限点。
 *
 * <h2>为什么没有「详情」接口</h2>
 * 会话审计需要两段式（列表给预览、点开才读正文并留痕），因为正文是用户原文；
 * 而这四张列表本身就是元数据或已脱敏摘要（工具调用只存 ≤512 字 digest、
 * 多模态结果只给 200 字预览、记忆与摘要已被上限约束），一次返回足够，
 * 也就不存在「读一次留一条审计」的必要。
 *
 * <h2>社区审核命中 / 中英翻译不在这里</h2>
 * 它们属于 {@code community} / {@code localization} 模块的表，接口留在各自模块
 * （{@code GET /admin/v1/community/moderation-hits}、{@code GET /admin/v1/localizations/audit}），
 * 前端在同一个「AI 审计」页用页签聚合。这样 {@code AiModuleBoundaryTest} 守的
 * 「ai 不反向依赖业务模块」才不被破例。
 */
@RestController
@RequestMapping("/admin/v1/ai/audit")
@RequiredArgsConstructor
public class AdminAiAuditController {

    private final AiAuditService service;

    /** 工具调用流水：按 id 倒序，可按用户 / 会话 / 工具名 / 成败过滤。 */
    @GetMapping("/tool-calls")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<AiAuditDtos.ToolCallRow>> toolCalls(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) Boolean ok,
            @Valid PageQuery query) {
        return ApiResponse.ok(service.toolCalls(userId, sessionId, toolName, ok, query));
    }

    /**
     * 长期记忆：默认只看生效行。
     *
     * @param includeDeleted 传 {@code true} 时连逻辑删除的记录一起列——审计要能回答
     *                       「系统曾经记过什么、什么时候被删的」
     */
    @GetMapping("/memories")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<AiAuditDtos.MemoryRow>> memories(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String memoryKey,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            @Valid PageQuery query) {
        return ApiResponse.ok(service.memories(userId, memoryKey, keyword, includeDeleted, query));
    }

    /** 滚动摘要：只列已经有摘要的会话。 */
    @GetMapping("/summaries")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<AiAuditDtos.SummaryRow>> summaries(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery query) {
        return ApiResponse.ok(service.summaries(userId, keyword, query));
    }

    /** 多模态识别任务：{@code bizType} 1 血糖 / 2 餐食，{@code taskStatus} 0~3。 */
    @GetMapping("/ocr-tasks")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<AiAuditDtos.OcrTaskRow>> ocrTasks(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer bizType,
            @RequestParam(required = false) Integer taskStatus,
            @Valid PageQuery query) {
        return ApiResponse.ok(service.ocrTasks(userId, bizType, taskStatus, query));
    }
}
