package com.threadfit.glucopilot.auth.controller;

import com.threadfit.glucopilot.auth.dto.AuthDtos;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.HttpHeaderNames;
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
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/wechat/sessions")
    public ApiResponse<AuthDtos.SessionResponse> wechatLogin(@Valid @RequestBody AuthDtos.WechatLoginRequest request) {
        return ApiResponse.ok(authService.loginByWechat(request));
    }

    @PostMapping("/sms/codes")
    public ApiResponse<AuthDtos.SmsCodeResponse> sendSms(@Valid @RequestBody AuthDtos.SmsCodeRequest request) {
        return ApiResponse.ok(authService.sendSmsCode(request));
    }

    @PostMapping("/phone/sessions")
    public ApiResponse<AuthDtos.SessionResponse> phoneLogin(@Valid @RequestBody AuthDtos.PhoneLoginRequest request) {
        return ApiResponse.ok(authService.loginByPhone(request));
    }

    @PostMapping("/email/codes")
    public ApiResponse<AuthDtos.EmailCodeResponse> sendEmail(@Valid @RequestBody AuthDtos.EmailCodeRequest request) {
        return ApiResponse.ok(authService.sendEmailCode(request));
    }

    @PostMapping("/email/sessions")
    public ApiResponse<AuthDtos.SessionResponse> emailLogin(@Valid @RequestBody AuthDtos.EmailLoginRequest request) {
        return ApiResponse.ok(authService.loginByEmail(request));
    }

    @DeleteMapping("/sessions/current")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(resolveBearer(request));
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
