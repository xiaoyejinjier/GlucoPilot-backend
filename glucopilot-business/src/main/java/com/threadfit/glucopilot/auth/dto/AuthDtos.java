package com.threadfit.glucopilot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    public static class EmailCodeRequest {
        @NotBlank
        @Email(message = "邮箱格式不正确")
        @Size(max = 128)
        private String email;
        /** login | bind */
        @NotBlank
        private String scene;
    }

    @Data
    public static class EmailLoginRequest {
        @NotBlank
        @Email(message = "邮箱格式不正确")
        @Size(max = 128)
        private String email;
        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        private String emailCode;
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

    @Data
    @Builder
    public static class EmailCodeResponse {
        private Integer expireIn;
    }

    /** 会话解析结果：userId + 账号状态（null 视为无效会话）。 */
    public record SessionPrincipal(Long userId, Integer accountStatus) {
    }
}
