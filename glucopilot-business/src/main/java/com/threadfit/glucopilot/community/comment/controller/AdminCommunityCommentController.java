package com.threadfit.glucopilot.community.comment.controller;

import static com.threadfit.glucopilot.community.comment.dto.AdminCommentDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.comment.service.AdminCommunityCommentService;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 社区评论（管理端）的运营接口。
 *
 * <h2>为什么这里不再要求 hasRole('admin')</h2>
 * 写操作原本写的是 @ss.hasRole('admin') and @ss.hasPermi('content:news:edit')，但 RuoYi 的
 * hasRole('admin') 只对 user_id=1（超管）成立——它把「谁能治理社区」这件事钉死在了一个账号上，
 * 运营一旦换人就得改代码。授权应当由菜单/权限点决定：现在只要求 content:news:edit，
 * 谁能拿到这个权限点，谁就能审核/隐藏/恢复，增减人只改 sys_role_menu。
 *
 * <h2>读接口仍是 content:news:list</h2>
 * 读与写分属两个权限点：只给「看」的角色（如只读运营）不该因为能看列表就获得审核权。
 */@RestController
@RequestMapping("/admin/v1/community")
@RequiredArgsConstructor
public class AdminCommunityCommentController {
    private final AdminCommunityCommentService service;
    private final CommunityModerationQueue queue;

    /**
     * 评论审核列表（含被 AI 隐藏的评论）。
     *
     * <p>与恢复接口配套：这张列表回答问题"今天被藏了哪些、为什么"，恢复接口负责"藏错了要放开"。 只有列表没有恢复，误判无法纠正；只有恢复没有列表，运营根本找不出误判。
     */
    @GetMapping("/comments")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<AdminComment>> comments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String verdict,
            @RequestParam(required = false) Integer hidden,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery q) {
        return ApiResponse.ok(service.adminComments(status, verdict, hidden, keyword, q));
    }

    /**
     * 主动设置一条评论的可见性：隐藏 / 恢复显示。
     *
     * <p>为什么需要"主动隐藏"：AI 只能判它见过的内容，而运营会遇到机器放过、实际该压下去的东西 （广告变体、擦边、踩线话题）。只有"恢复"没有"隐藏"，运营手里就只有一半的权力。 与
     * AI 判违规共用同一列 {@code is_hidden}，但不动 {@code ai_verdict}：两种来源的事实都保留。
     *
     * <p>幂等：已经处于目标状态时返回成功（重复点击不报错）；评论不存在或已删除返回 404。
     */
    @PutMapping("/comments/{id}/visibility")
    @PreAuthorize("@ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> setCommentVisibility(
            @PathVariable long id, @Valid @RequestBody CommentVisibility request) {
        queue.setCommentHidden(RequestContext.requireOpsId(), id, request.hidden());
        return ApiResponse.ok();
    }

    /**
     * 恢复被 AI 判违规并隐藏的评论（误杀兜底）。
     *
     * <p>为什么必须有人工恢复入口：评论是"先发后审"，机器判 REJECT 会立即隐藏； 没有恢复入口意味着一次误判只能改库才能纠正，而误判一定会有。
     * 幂等：评论不存在或本来就没有被隐藏时返回成功（运营重复点击不该报错）。
     */
    @PostMapping("/comments/{id}/restore")
    @PreAuthorize("@ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> restoreComment(@PathVariable long id) {
        queue.restoreComment(RequestContext.requireOpsId(), id);
        return ApiResponse.ok();
    }
}
