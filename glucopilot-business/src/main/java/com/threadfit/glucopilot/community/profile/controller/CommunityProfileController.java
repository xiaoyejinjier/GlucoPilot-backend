package com.threadfit.glucopilot.community.profile.controller;

import static com.threadfit.glucopilot.community.profile.dto.ProfileDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.profile.service.CommunityProfileService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommunityProfileController {
    private final CommunityProfileService service;

    private long user() {
        return RequestContext.requireUserId();
    }

    @GetMapping("/community/profiles")
    public ApiResponse<PageResult<Profile>> search(
            @RequestParam String keyword, @Valid PageQuery q) {
        return ApiResponse.ok(service.search(user(), keyword, q));
    }

    @GetMapping("/community/profiles/{key}")
    public ApiResponse<Profile> profile(@PathVariable String key) {
        long u = user();
        return ApiResponse.ok(service.profile(u, key));
    }
}
