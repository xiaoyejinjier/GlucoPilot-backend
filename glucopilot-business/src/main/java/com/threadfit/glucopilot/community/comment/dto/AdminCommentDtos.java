package com.threadfit.glucopilot.community.comment.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

public final class AdminCommentDtos {
    private AdminCommentDtos() {}

    /** 管理端设置评论可见性：true 隐藏，false 恢复显示。用包装类型是为了让漏传字段报 400 而不是默认 false。 */
    public record CommentVisibility(@NotNull Boolean hidden) {}

    /**
     * 管理端评论审核列表项。
     *
     * <p>带原文：管理员必须读到内容才能判断"是不是误杀"；不带用户敏感字段， 作者沿用社区口径的 {@code u<id>} 键 + 昵称。 判定字段全部来自 V43 的 {@code
     * ai_*} 列，其中 {@code aiCategory}/{@code aiSeverity}/{@code aiConfidence} 只在 REJECT 时有值（PASS 写
     * null，用来区分"没审过"与"审过且干净"）。
     */
    public record AdminComment(
            String id,
            long newsId,
            String articleTitle,
            String content,
            String authorKey,
            String authorNickname,
            String aiStatus,
            String aiVerdict,
            String aiCategory,
            Integer aiSeverity,
            Integer aiConfidence,
            String aiReason,
            String aiModel,
            String aiPromptVer,
            boolean hidden,
            LocalDateTime createdAt) {}
}
