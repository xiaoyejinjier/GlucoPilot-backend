package com.threadfit.glucopilot.content.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.content.dto.AdminCmsDtos;
import com.threadfit.glucopilot.content.service.AdminCmsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class AdminCmsController {

    private final AdminCmsService adminCmsService;

    @GetMapping("/news")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_NEWS_LIST + "')")
    public ApiResponse<List<AdminCmsDtos.NewsItem>> listNews() {
        return ApiResponse.ok(adminCmsService.listNews());
    }

    @PostMapping("/news")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_NEWS_EDIT + "')")
    public ApiResponse<AdminCmsDtos.NewsItem> createNews(@Valid @RequestBody AdminCmsDtos.NewsSaveRequest request) {
        return ApiResponse.ok(adminCmsService.saveNews(request));
    }

    @PutMapping("/news/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_NEWS_EDIT + "')")
    public ApiResponse<AdminCmsDtos.NewsItem> updateNews(
            @PathVariable Long id, @Valid @RequestBody AdminCmsDtos.NewsSaveRequest request) {
        request.setId(id);
        return ApiResponse.ok(adminCmsService.saveNews(request));
    }

    @DeleteMapping("/news/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_NEWS_EDIT + "')")
    public ApiResponse<Void> removeNews(@PathVariable Long id) {
        adminCmsService.removeNews(id);
        return ApiResponse.ok();
    }

    @PutMapping("/news/{id}/status")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_NEWS_EDIT + "')")
    public ApiResponse<Void> setNewsStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        adminCmsService.setNewsStatus(id, body.get("status"));
        return ApiResponse.ok();
    }

    @GetMapping("/banners")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_BANNER_MANAGE + "')")
    public ApiResponse<List<AdminCmsDtos.BannerItem>> listBanners() {
        return ApiResponse.ok(adminCmsService.listBanners());
    }

    @PostMapping("/banners")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_BANNER_MANAGE + "')")
    public ApiResponse<AdminCmsDtos.BannerItem> createBanner(@Valid @RequestBody AdminCmsDtos.BannerSaveRequest request) {
        return ApiResponse.ok(adminCmsService.saveBanner(request));
    }

    @PutMapping("/banners/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_BANNER_MANAGE + "')")
    public ApiResponse<AdminCmsDtos.BannerItem> updateBanner(
            @PathVariable Long id, @Valid @RequestBody AdminCmsDtos.BannerSaveRequest request) {
        request.setId(id);
        return ApiResponse.ok(adminCmsService.saveBanner(request));
    }

    @DeleteMapping("/banners/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_BANNER_MANAGE + "')")
    public ApiResponse<Void> removeBanner(@PathVariable Long id) {
        adminCmsService.removeBanner(id);
        return ApiResponse.ok();
    }
}
