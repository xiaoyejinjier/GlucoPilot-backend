package com.threadfit.glucopilot.content.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.content.dto.ContentDtos;
import com.threadfit.glucopilot.content.service.ContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @GetMapping("/banners")
    public ApiResponse<List<ContentDtos.BannerItem>> banners() {
        return ApiResponse.ok(contentService.listBanners());
    }

    @GetMapping("/news")
    public ApiResponse<PageResult<ContentDtos.NewsListItem>> news(
            @RequestParam(required = false) Integer channelCode,
            @Valid PageQuery query) {
        return ApiResponse.ok(contentService.pageNews(channelCode, query));
    }

    @GetMapping("/news/{id}")
    public ApiResponse<ContentDtos.NewsDetail> newsDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(contentService.getNewsDetail(id, RequestContext.getUserId()));
    }

    @PutMapping("/news/{id}/actions")
    public ApiResponse<ContentDtos.NewsActionResult> newsActions(
            @PathVariable("id") Long id,
            @RequestBody ContentDtos.NewsActionRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(contentService.updateNewsAction(userId, id, request));
    }

    @GetMapping("/food-categories")
    public ApiResponse<List<ContentDtos.FoodCategoryItem>> foodCategories() {
        return ApiResponse.ok(contentService.listFoodCategories());
    }

    @GetMapping("/foods")
    public ApiResponse<PageResult<ContentDtos.FoodItem>> foods(
            @RequestParam(required = false) Integer categoryCode,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery query) {
        return ApiResponse.ok(contentService.pageFoods(categoryCode, keyword, query));
    }
}
