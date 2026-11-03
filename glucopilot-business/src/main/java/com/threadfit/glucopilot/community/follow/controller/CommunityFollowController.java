package com.threadfit.glucopilot.community.follow.controller;

import static com.threadfit.glucopilot.community.follow.dto.FollowDtos.*;
import static com.threadfit.glucopilot.community.profile.dto.ProfileDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.follow.service.CommunityFollowService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommunityFollowController {
    private final CommunityFollowService service;

    private long user() {
        return RequestContext.requireUserId();
    }

    @PutMapping("/community/profiles/{key}/following")
    public ApiResponse<Profile> follow(
            @PathVariable String key, @Valid @RequestBody Following request) {
        return ApiResponse.ok(service.follow(user(), key, request.following()));
    }

    @GetMapping("/community/following")
    public ApiResponse<PageResult<Profile>> following(
            @RequestParam(required = false) String keyword, @Valid PageQuery q) {
        return ApiResponse.ok(service.follows(user(), keyword, q));
    }
}
