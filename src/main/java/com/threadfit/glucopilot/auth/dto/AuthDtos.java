package com.threadfit.glucopilot.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Data;

public final class AuthDtos {

    private AuthDtos() {
    }

    @Data
    public static class WechatLoginRequest {
        @NotBlank
        private String code;
    }

    @Data
    public static class SmsCodeRequest {
        @NotBlank
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        private String phone;
        /** login | bind */
        @NotBlank
        private String scene;
    }

    @Data
    public static class PhoneLoginRequest {
        @NotBlank
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        private String phone;
        @NotBlank
        private String smsCode;
    }

    @Data
    @Builder
    public static class SessionResponse {
        private String accessToken;
        private Long expiresIn;
        private Boolean isNewUser;
        private Integer accountStatus;
        private Boolean needDisclaimer;
    }

    @Data
    @Builder
    public static class SmsCodeResponse {
        private Integer expireIn;
    }
}
