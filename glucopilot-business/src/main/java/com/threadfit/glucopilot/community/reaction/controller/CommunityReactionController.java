package com.threadfit.glucopilot.community.reaction.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.reaction.service.CommunityReactionService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommunityReactionController {
    private final CommunityReactionService service;

    private long user() {
        return RequestContext.requireUserId();
    }

    @GetMapping("/community/collections")
    public ApiResponse<PageResult<com.threadfit.glucopilot.content.dto.ContentDtos.NewsListItem>>
            collections(@RequestParam(required = false) String keyword, @Valid PageQuery q) {
        return ApiResponse.ok(service.collections(user(), keyword, q));
    }

    @GetMapping("/community/likes")
    public ApiResponse<PageResult<com.threadfit.glucopilot.content.dto.ContentDtos.NewsListItem>>
            likes(@RequestParam(required = false) String keyword, @Valid PageQuery q) {
        return ApiResponse.ok(service.likes(user(), keyword, q));
    }
}
