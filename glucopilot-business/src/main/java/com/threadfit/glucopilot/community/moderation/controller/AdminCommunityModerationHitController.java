package com.threadfit.glucopilot.community.moderation.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.community.moderation.dto.ModerationHitAuditDtos;
import com.threadfit.glucopilot.community.moderation.entity.CommunityModerationHit;
import com.threadfit.glucopilot.community.moderation.mapper.CommunityModerationMapper;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社区审核命中明细（只读），供管理端「AI 审计 → 内容审核」页签使用。
 *
 * <h2>为什么在 community 模块，却用 {@code ai:chat:audit} 权限</h2>
 * 表属于社区（{@code community_moderation_hit}），SQL 放在本模块是「谁的表谁写 SQL」；
 * 但这一页在产品上是 AI 审计的一个页签，看的是「模型判了什么」，与
 * {@code content:news:list} 那种「看内容本身」不是同一件事。若改挂 {@code content:news:list}，
 * 会出现「能看审核命中却打不开审计页」的错配，所以沿用审计权限点。
 *
 * <h2>为什么只给偏移量与哈希、不给原文</h2>
 * 这张表刻意<b>不存</b>命中原文（V43 的隐私设计）：只留 {@code start_offset/end_offset}
 * 与 {@code snippet_hash}。因此这里也不做「点开看原文」——真要读原文，
 * 走内容管理或会话审计对应的接口，那两处各自有自己的留痕策略。
 */
@RestController
@RequestMapping("/admin/v1/community/moderation-hits")
@RequiredArgsConstructor
public class AdminCommunityModerationHitController {

    private final CommunityModerationMapper mapper;

    /**
     * 命中明细分页：按 id 倒序（最新判定在前）。
     *
     * @param targetType   ARTICLE / COMMENT，可空
     * @param categoryCode 命中类别 code（如 medical_advice），可空
     * @param severity     1 低 / 2 中 / 3 高，可空
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<ModerationHitAuditDtos.HitRow>> list(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) Integer severity,
            @Valid PageQuery query) {
        IPage<CommunityModerationHit> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<CommunityModerationHit> result = mapper.selectHitAuditPage(
                page, normalize(targetType), normalize(categoryCode), severity);
        List<ModerationHitAuditDtos.HitRow> rows = result.getRecords().stream()
                .map(hit -> new ModerationHitAuditDtos.HitRow(
                        hit.getId() == null ? 0L : hit.getId(),
                        hit.getTargetType(),
                        hit.getTargetId() == null ? 0L : hit.getTargetId(),
                        hit.getRevision(),
                        hit.getCategoryCode(),
                        hit.getSeverity() == null ? 0 : hit.getSeverity(),
                        hit.getConfidence(),
                        hit.getStartOffset(),
                        hit.getEndOffset(),
                        hit.getSnippetLen(),
                        hit.getSnippetHash(),
                        hit.getReason(),
                        hit.getModelCode(),
                        hit.getPromptVer(),
                        hit.getCreatedAt()))
                .toList();
        return ApiResponse.ok(PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows));
    }

    /** 空白视为「不过滤」；这里不做长度限制，类别与目标类型都是短枚举。 */
    private static String normalize(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
