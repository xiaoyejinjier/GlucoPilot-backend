package com.threadfit.glucopilot.profile.health.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.DisclaimerAgreementRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthProfileSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.ProfileUpdateRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.SettingUpdateRequest;
import com.threadfit.glucopilot.profile.health.service.ProfileHealthService;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.DisclaimerAgreementResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.HealthSaveResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.ProfileUpdateResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.SettingUpdateResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.UserMe;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端「我的」：资料 / 健康档案 / 偏好设置。
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserMeController {

    private final ProfileHealthService profileHealthService;

    @GetMapping
    public ApiResponse<UserMe> getMe() {
        return ApiResponse.ok(profileHealthService.getUserMe(RequestContext.requireUserId()));
    }

    @PatchMapping("/profile")
    public ApiResponse<ProfileUpdateResult> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.ok(profileHealthService.updateProfile(request, RequestContext.requireUserId()));
    }

    @PutMapping("/health-profile")
    public ApiResponse<HealthSaveResult> saveHealthProfile(@Valid @RequestBody HealthProfileSaveRequest request) {
        return ApiResponse.ok(profileHealthService.saveHealthProfile(request, RequestContext.requireUserId()));
    }

    @PatchMapping("/settings")
    public ApiResponse<SettingUpdateResult> updateSettings(@Valid @RequestBody SettingUpdateRequest request) {
        return ApiResponse.ok(profileHealthService.updateSettings(request, RequestContext.requireUserId()));
    }

    @PostMapping("/settings/disclaimer-agreement")
    public ApiResponse<DisclaimerAgreementResult> agreeDisclaimer(
            @Valid @RequestBody DisclaimerAgreementRequest request) {
        return ApiResponse.ok(profileHealthService.agreeDisclaimer(request, RequestContext.requireUserId()));
    }
}