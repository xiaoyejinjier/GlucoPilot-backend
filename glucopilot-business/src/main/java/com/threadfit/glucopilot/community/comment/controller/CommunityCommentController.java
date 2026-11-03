package com.threadfit.glucopilot.community.comment.controller;

import static com.threadfit.glucopilot.community.comment.dto.CommentDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.comment.service.CommunityCommentService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommunityCommentController {
    private final CommunityCommentService service;

    private long user() {
        return RequestContext.requireUserId();
    }

    @GetMapping("/news/{id}/comments")
    public ApiResponse<PageResult<Comment>> comments(@PathVariable long id, @Valid PageQuery q) {
        return ApiResponse.ok(service.comments(user(), id, q));
    }

    /**
     * 「我的评论」：我发出的评论与回复（含被 AI 隐藏的），新的在前。
     *
     * <p>之前 C 端只有 {@code /news/{id}/comments}，任何评论操作都要先知道文章 id， 用户发完评论就再也找不回自己说过什么；被机器隐藏时更是完全无从得知。
     */
    @GetMapping("/community/comments/mine")
    public ApiResponse<PageResult<MyComment>> myComments(@Valid PageQuery q) {
        return ApiResponse.ok(service.myComments(user(), q));
    }

    @GetMapping("/news/{id}/comments/{commentId}/context")
    public ApiResponse<CommentContext> commentContext(
            @PathVariable long id, @PathVariable long commentId) {
        return ApiResponse.ok(service.commentContext(user(), id, commentId));
    }

    @PostMapping("/news/{id}/comments")
    public ApiResponse<Comment> comment(
            @PathVariable long id, @Valid @RequestBody CommentRequest request) {
        return ApiResponse.ok(service.addComment(user(), id, request));
    }

    @PostMapping("/news/{id}/comments/{targetId}/replies")
    public ApiResponse<Comment> reply(
            @PathVariable long id,
            @PathVariable long targetId,
            @Valid @RequestBody CommentRequest request) {
        return ApiResponse.ok(
                service.addComment(
                        user(),
                        id,
                        new CommentRequest(
                                request.content(), request.clientRequestId(), targetId)));
    }

    @GetMapping("/news/{id}/comments/{rootId}/replies")
    public ApiResponse<Replies> replies(
            @PathVariable long id,
            @PathVariable long rootId,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(service.replies(user(), id, rootId, afterId, pageSize));
    }

    @DeleteMapping("/news/{id}/comments/{commentId}")
    public ApiResponse<Void> delete(@PathVariable long id, @PathVariable long commentId) {
        service.deleteComment(user(), id, commentId);
        return ApiResponse.ok();
    }
}
