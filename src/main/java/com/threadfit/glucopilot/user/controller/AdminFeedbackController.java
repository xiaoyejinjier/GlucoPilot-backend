package com.threadfit.glucopilot.user.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.user.dto.AdminFeedbackDtos;
import com.threadfit.glucopilot.user.feedback.AdminFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端反馈查询与审批回复。 */
@RestController
@RequestMapping("/admin/v1/feedbacks")
@RequiredArgsConstructor
public class AdminFeedbackController {

    private final AdminFeedbackService adminFeedbackService;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FEEDBACK_MANAGE + "')")
    public ApiResponse<PageResult<AdminFeedbackDtos.FeedbackItem>> page(
            @RequestParam(required = false) Integer feedbackType,
            @RequestParam(required = false) Integer replyStatus,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery query) {
        return ApiResponse.ok(adminFeedbackService.page(feedbackType, replyStatus, keyword, query));
    }

    @PutMapping("/{id}/reply")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FEEDBACK_REPLY + "')")
    public ApiResponse<AdminFeedbackDtos.ReplyResult> reply(
            @PathVariable Long id,
            @Valid @RequestBody AdminFeedbackDtos.ReplyRequest request) {
        return ApiResponse.ok(adminFeedbackService.reply(RequestContext.requireOpsId(), id, request));
    }
}
