package com.threadfit.glucopilot.community.moderation.dto;

import java.time.LocalDateTime;

/**
 * 审核命中明细的响应契约（管理端「AI 审计 → 内容审核」）。
 *
 * <p>字段与 {@code community_moderation_hit} 一一对应，唯一的加工是「没有原文」这件事本身：
 * {@code startOffset/endOffset} 是<b>1 基、结束位置不含</b>的字符区间（定位失败为 null），
 * {@code snippetHash} 是命中片段的 sha256 前 64 位十六进制——两者合起来能证明
 * 「同一段文字被再次命中」，但还原不出原文。
 */
public final class ModerationHitAuditDtos {

    private ModerationHitAuditDtos() {
    }

    /**
     * @param severity 1 低 / 2 中 / 3 高（DDL 上 {@code chk_cmh_severity} 限定 1~3）
     * @param reason   模型给出的说明，刻意要求「不摘抄原文」
     */
    public record HitRow(long id,
                         String targetType,
                         long targetId,
                         Integer revision,
                         String categoryCode,
                         int severity,
                         Integer confidence,
                         Integer startOffset,
                         Integer endOffset,
                         Integer snippetLen,
                         String snippetHash,
                         String reason,
                         String modelCode,
                         String promptVer,
                         LocalDateTime createdAt) {
    }
}
