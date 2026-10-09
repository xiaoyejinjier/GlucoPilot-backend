package com.threadfit.glucopilot.auth.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.HttpHeaderNames;
import com.threadfit.glucopilot.auth.dto.OpsAuthDtos;
import com.threadfit.glucopilot.auth.service.OpsAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/auth")
@RequiredArgsConstructor
public class OpsAuthController {

    private final OpsAuthService opsAuthService;

    @PostMapping("/sessions")
    public ApiResponse<OpsAuthDtos.SessionResponse> login(@Valid @RequestBody OpsAuthDtos.LoginRequest request) {
        return ApiResponse.ok(opsAuthService.login(request));
    }

    @DeleteMapping("/sessions/current")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        opsAuthService.logout(resolveBearer(request));
        return ApiResponse.ok();
    }

    private static String resolveBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaderNames.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7).trim();
    }
}
