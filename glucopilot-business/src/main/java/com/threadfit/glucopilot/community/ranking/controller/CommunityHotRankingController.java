package com.threadfit.glucopilot.community.ranking.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.ranking.dto.HotRankingDtos;
import com.threadfit.glucopilot.community.ranking.service.CommunityHotRankingService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community")
public class CommunityHotRankingController {
    private final CommunityHotRankingService service;

    public CommunityHotRankingController(CommunityHotRankingService service) {
        this.service = service;
    }

    @GetMapping("/hot-articles")
    public ApiResponse<HotRankingDtos.HotArticlesResponse> hotArticles(
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.hotArticles(RequestContext.requireUserId(), limit));
    }
}
