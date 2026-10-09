package com.threadfit.glucopilot.user.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.user.dto.FeedbackDtos;
import com.threadfit.glucopilot.user.feedback.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端反馈留言中心。 */
@RestController
@RequestMapping("/api/v1/feedbacks")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ApiResponse<FeedbackDtos.SubmitResult> submit(@Valid @RequestBody FeedbackDtos.SubmitRequest request) {
        return ApiResponse.ok(feedbackService.submit(RequestContext.requireUserId(), request));
    }

    @GetMapping
    public ApiResponse<PageResult<FeedbackDtos.FeedbackItem>> myFeedbacks(@Valid PageQuery query) {
        return ApiResponse.ok(feedbackService.page(RequestContext.requireUserId(), query));
    }
}
