package com.threadfit.glucopilot.user.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
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
    private final HealthProfileService healthProfileService;

    @GetMapping
    public ApiResponse<UserDtos.MeResponse> me() {
        return ApiResponse.ok(userAccountService.me(RequestContext.requireUserId()));
    }

    @PatchMapping("/profile")
    public ApiResponse<UserDtos.UpdatedResult> patchProfile(
            @Valid @RequestBody UserDtos.ProfilePatchRequest request) {
        return ApiResponse.ok(userAccountService.patchProfile(RequestContext.requireUserId(), request));
    }

    /**
     * 健康档案全量保存（PUT，幂等覆盖）。见接口 Javadoc：null/空白字段 = 清空回未填写，
     * diabetesTypeText 服务端按 diabetesType 派生、忽略客户端传值，diagnosedYm 不得晚于当前月。
     * 业务实现委托 profile.health 模块的 HealthProfileService，本控制器只做路由聚合。
     */
    @PutMapping("/health-profile")
    public ApiResponse<HealthView> saveHealth(
            @Valid @RequestBody HealthSaveRequest request) {
        return ApiResponse.ok(healthProfileService.saveHealth(RequestContext.requireUserId(), request));
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
