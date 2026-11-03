package com.threadfit.glucopilot.community.reaction.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.reaction.service.CommunityArticleActionService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommunityArticleActionController {
    private final CommunityArticleActionService contentService;

    @PutMapping("/news/{id}/actions")
    public ApiResponse<
                    com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionResult>
            newsActions(
                    @PathVariable("id") Long id,
                    @RequestBody
                            com.threadfit.glucopilot.community.reaction.dto.ReactionDtos
                                            .NewsActionRequest
                                    request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(contentService.updateNewsAction(userId, id, request));
    }
}
