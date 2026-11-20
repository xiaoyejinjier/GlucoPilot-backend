package com.threadfit.glucopilot.device.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.cloud.DeviceCloudCredentialService;
import com.threadfit.glucopilot.device.cloud.DeviceCloudProperties;
import com.threadfit.glucopilot.device.cloud.DeviceCloudPullService;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.entity.DeviceCloudCredential;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 华为健康云授权与云拉取。
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceCloudController {

    private final DeviceCloudCredentialService credentialService;
    private final DeviceCloudPullService pullService;
    private final DeviceCloudProperties cloudProperties;

    @GetMapping("/cloud/huawei/authorize-url")
    public ApiResponse<DeviceDtos.HuaweiAuthorizeUrlResponse> authorizeUrl(
            @RequestParam(required = false) Long deviceId) {
        DeviceCloudCredentialService.AuthorizeSession session =
                credentialService.beginHuaweiAuthorize(RequestContext.requireUserId(), deviceId);
        return ApiResponse.ok(DeviceDtos.HuaweiAuthorizeUrlResponse.builder()
                .authorizeUrl(session.authorizeUrl())
                .state(session.state())
                .build());
    }

    /**
     * 华为 OAuth 回调：匿名；成功/失败均 302 到配置的 returnUrl。
     */
    @GetMapping("/cloud/huawei/callback")
    public void huaweiCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletResponse response) throws IOException {
        String returnUrl = cloudProperties.getHuawei() != null
                ? cloudProperties.getHuawei().getReturnUrl()
                : "";
        if (!StringUtils.hasText(returnUrl)) {
            returnUrl = "https://example.invalid/cloud-auth-done";
        }
        try {
            if (StringUtils.hasText(error)) {
                redirect(response, returnUrl, false, error);
                return;
            }
            credentialService.completeHuaweiCallback(code, state);
            redirect(response, returnUrl, true, null);
        } catch (BizException ex) {
            redirect(response, returnUrl, false, ex.getMessage());
        } catch (Exception ex) {
            redirect(response, returnUrl, false, "授权失败");
        }
    }

    @GetMapping("/{id:\\d+}/cloud-auth")
    public ApiResponse<DeviceDtos.CloudAuthStatus> cloudAuth(@PathVariable Long id) {
        Long userId = RequestContext.requireUserId();
        credentialService.requireOwnedDevice(userId, id);
        DeviceCloudCredential row = credentialService.findActiveByDevice(userId, id);
        if (row == null) {
            return ApiResponse.ok(DeviceDtos.CloudAuthStatus.builder().linked(false).build());
        }
        return ApiResponse.ok(DeviceDtos.CloudAuthStatus.builder()
                .linked(true)
                .expiresAt(row.getTokenExpiresAt())
                .scopes(row.getScopes())
                .build());
    }

    @DeleteMapping("/{id:\\d+}/cloud-auth")
    public ApiResponse<Void> revokeCloudAuth(@PathVariable Long id) {
        credentialService.revokeByDevice(RequestContext.requireUserId(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/cloud-pull")
    public ApiResponse<DeviceDtos.CloudPullResponse> cloudPull(
            @Valid @RequestBody(required = false) DeviceDtos.CloudPullRequest request) {
        Long deviceId = request == null ? null : request.getDeviceId();
        return ApiResponse.ok(pullService.pull(RequestContext.requireUserId(), deviceId));
    }

    private static void redirect(HttpServletResponse response, String base, boolean ok, String message)
            throws IOException {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base);
        builder.replaceQueryParam("cloudAuth", ok ? "1" : "0");
        if (StringUtils.hasText(message)) {
            builder.replaceQueryParam("message", URLEncoder.encode(message, StandardCharsets.UTF_8));
        }
        response.sendRedirect(builder.build(true).toUriString());
    }
}
