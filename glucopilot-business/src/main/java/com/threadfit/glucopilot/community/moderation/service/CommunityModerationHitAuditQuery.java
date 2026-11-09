package com.threadfit.glucopilot.community.moderation.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.ai.audit.service.ModerationHitAuditQuery;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.community.moderation.entity.CommunityModerationHit;
import com.threadfit.glucopilot.community.moderation.mapper.CommunityModerationMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 审核命中的审计读实现：{@link ModerationHitAuditQuery} 的社区侧落地。
 *
 * <h2>为什么实现放在这里</h2>
 * 表 {@code community_moderation_hit} 是社区的表，SQL 就由社区来写；
 * 对外的路径（{@code /admin/v1/ai/audit/moderation-hits}）、权限（{@code ai:chat:audit}）
 * 与行结构（{@link AiAuditDtos.ModerationHitRow}）都由 ai 模块拥有。依赖方向
 * {@code community → ai} 因此不变，{@code AiModuleBoundaryTest} 依旧成立。
 *
 * <h2>为什么要把实体翻译成 DTO 而不是直接返回实体</h2>
 * 实体带着逻辑删除审计列与 {@code hit_source} 这类内部字段；审计页不需要、
 * 也不该让前端拿到它们。翻译就在这里做，ai 侧只看到「能对外说的那 15 个字段」。
 */
@Component
@RequiredArgsConstructor
public class CommunityModerationHitAuditQuery implements ModerationHitAuditQuery {

    private final CommunityModerationMapper mapper;

    @Override
    public PageResult<AiAuditDtos.ModerationHitRow> page(String targetType, String categoryCode,
                                                         Integer severity, PageQuery query) {
        IPage<CommunityModerationHit> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<CommunityModerationHit> result = mapper.selectHitAuditPage(
                page, targetType, categoryCode, severity);
        List<AiAuditDtos.ModerationHitRow> rows = result.getRecords().stream()
                .map(CommunityModerationHitAuditQuery::toRow)
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows);
    }

    /** 空值兜底成 0 / null：这条记录一旦写坏，审计页也不该因此 500。 */
    private static AiAuditDtos.ModerationHitRow toRow(CommunityModerationHit hit) {
        return new AiAuditDtos.ModerationHitRow(
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
                hit.getCreatedAt());
    }
}
