package com.threadfit.glucopilot.community.article.controller;

import static com.threadfit.glucopilot.community.article.dto.PublicationDtos.*;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.article.service.CommunityPublicationService;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/community")
public class AdminCommunityController {
    private final CommunityPublicationService service;
    private final CommunityModerationQueue queue;

    public AdminCommunityController(
            CommunityPublicationService service, CommunityModerationQueue queue) {
        this.service = service;
        this.queue = queue;
    }

    @GetMapping("/articles")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<Article>> articles(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery q) {
        return ApiResponse.ok(service.adminArticles(status, source, keyword, q));
    }

    @GetMapping("/articles/{id}")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<Article> article(@PathVariable long id) {
        return ApiResponse.ok(service.get(null, id, true));
    }

    @PostMapping("/articles/{id}/review")
    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Article> review(@PathVariable long id, @Valid @RequestBody Review request) {
        return ApiResponse.ok(service.review(RequestContext.requireOpsId(), id, request));
    }

    @PostMapping("/articles/{id}/retry-ai")
    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> retry(@PathVariable long id) {
        service.retry(RequestContext.requireOpsId(), id, true);
        return ApiResponse.ok();
    }
}
