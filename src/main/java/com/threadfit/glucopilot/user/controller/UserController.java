package com.threadfit.glucopilot.user.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.user.account.UserAccountService;
import com.threadfit.glucopilot.user.dto.UserDtos;
import com.threadfit.glucopilot.user.settings.UserSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端用户中心：资料、健康档案、偏好设置、免责声明与手机号。 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserAccountService userAccountService;
    private final UserSettingService userSettingService;

    @GetMapping
    public ApiResponse<UserDtos.MeResponse> me() {
        return ApiResponse.ok(userAccountService.me(RequestContext.requireUserId()));
    }

    @PatchMapping("/profile")
    public ApiResponse<UserDtos.UpdatedResult> patchProfile(
            @Valid @RequestBody UserDtos.ProfilePatchRequest request) {
        return ApiResponse.ok(userAccountService.patchProfile(RequestContext.requireUserId(), request));
    }

    @PutMapping("/health-profile")
    public ApiResponse<UserDtos.HealthView> saveHealth(
            @Valid @RequestBody UserDtos.HealthSaveRequest request) {
        return ApiResponse.ok(userAccountService.saveHealth(RequestContext.requireUserId(), request));
    }

    @PatchMapping("/settings")
    public ApiResponse<UserDtos.UpdatedResult> patchSettings(
            @Valid @RequestBody UserDtos.SettingsPatchRequest request) {
        return ApiResponse.ok(userSettingService.patchSettings(RequestContext.requireUserId(), request));
    }

    @PostMapping("/settings/disclaimer-agreement")
    public ApiResponse<UserDtos.DisclaimerAgreementResult> agreeDisclaimer(
            @Valid @RequestBody UserDtos.DisclaimerAgreementRequest request) {
        return ApiResponse.ok(userSettingService.agreeDisclaimer(RequestContext.requireUserId(), request));
    }

    @PatchMapping("/phone")
    public ApiResponse<UserDtos.PhoneBindResult> bindPhone(
            @Valid @RequestBody UserDtos.PhoneBindRequest request) {
        return ApiResponse.ok(userAccountService.bindPhone(RequestContext.requireUserId(), request));
    }
}
