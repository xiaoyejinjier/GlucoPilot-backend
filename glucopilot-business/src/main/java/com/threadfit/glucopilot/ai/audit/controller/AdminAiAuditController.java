package com.threadfit.glucopilot.ai.audit.controller;

import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.ai.audit.service.AiAuditService;
import com.threadfit.glucopilot.ai.audit.service.ModerationHitAuditQuery;
import com.threadfit.glucopilot.ai.audit.service.TranslationAuditQuery;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 审计的六个列表：工具调用 / 长期记忆 / 滚动摘要 / 多模态任务 / 审核命中 / 中英翻译
 * （会话审计的三个接口在 {@code /admin/v1/ai/audit/sessions/**}，见
 * {@code AdminChatSessionController} 与 {@code AdminChatContextController}）。
 *
 * <h2>权限</h2>
 * 全部 {@code ai:chat:audit}——与会话审计同一个点。理由：这些表要么直接由对话产生
 * （工具调用、摘要），要么决定下一次对话的输入（长期记忆），要么是同一批模型对
 * 用户内容的判定结果（审核命中、翻译缓存、多模态识别）；能看会话原文的人才该看到它们，
 * 不值得再拆权限点。
 *
 * <h2>为什么没有「详情」接口</h2>
 * 会话审计需要两段式（列表给预览、点开才读正文并留痕），因为正文是用户原文；
 * 而这六张列表本身就是元数据或已脱敏摘要（工具调用只存 ≤512 字 digest、
 * 审核命中只有位置与片段哈希、翻译与多模态只给 200 字预览），一次返回足够，
 * 也就不存在「读一次留一条审计」的必要。
 *
 * <h2>为什么后两个端点的实现在别的模块</h2>
 * 审核命中在 {@code community_moderation_hit}、翻译缓存在 {@code content_localization}，
 * 表的主人是社区与本地化模块，SQL 也就留在那边（谁的表谁写 SQL）。但**对外契约归 ai**：
 * 路径、权限、行结构都在本包，两个模块各自实现
 * {@link ModerationHitAuditQuery} / {@link TranslationAuditQuery} 端口。
 * 这样 URL 与前端只认一套，同时 {@code AiModuleBoundaryTest} 守的
 * 「ai 不反向依赖业务模块」不被破例。
 */
@RestController
@RequestMapping("/admin/v1/ai/audit")
@RequiredArgsConstructor
public class AdminAiAuditController {

    private final AiAuditService service;
    private final ModerationHitAuditQuery moderationHits;
    private final TranslationAuditQuery translations;

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

    /**
     * 内容审核命中明细：可按目标类型 / 命中类别 / 严重度过滤。
     *
     * <p>只返回位置与片段哈希，没有原文（表里本来就不存，见 V43 的隐私设计）。
     */
    @GetMapping("/moderation-hits")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<AiAuditDtos.ModerationHitRow>> moderationHits(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) Integer severity,
            @Valid PageQuery query) {
        return ApiResponse.ok(moderationHits.page(
                normalize(targetType), normalize(categoryCode), severity, query));
    }

    /** 翻译缓存：可按类别 / 状态过滤；只给 200 字译文预览与字符数。 */
    @GetMapping("/translations")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<AiAuditDtos.TranslationRow>> translations(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @Valid PageQuery query) {
        return ApiResponse.ok(translations.page(normalize(kind), normalize(status), query));
    }

    /** 空白视为「不过滤」；类别、状态、目标类型都是短枚举，不做长度限制。 */
    private static String normalize(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
