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
        /** login | bind | cancel（注销提交用 cancel；手动恢复已下线，不再有 recover）。 */
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
        /** login | bind | cancel（注销提交用 cancel；手动恢复已下线，不再有 recover）。 */
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

    /** 冷静期登录拦截：不下发 Token，只给一次性票据与脱敏信息。 */
    @Data
    @Builder
    public static class CancelRecoverIntercept {
        private Boolean recoverRequired;
        private String recoverTicket;
        /** 票据有效期（秒）。 */
        private Long ticketExpireIn;
        /** 剩余冷静期天数，向上取整，最小 1。 */
        private Integer remainingDays;
        /** 冷静期截止时间（epoch 毫秒）。 */
        private Long cancelDeadlineAt;
        private String usernameMasked;
        private String phoneMasked;
        /** 恢复后的账号状态：0=仍禁用，1=正常。 */
        private Integer recoverToStatus;
        private Boolean willStayDisabled;
    }

    @Data
    public static class ConfirmRecoverRequest {
        @NotBlank(message = "恢复凭据不能为空")
        private String ticket;
    }

    @Data
    @Builder
    public static class SessionResponse {
        private String accessToken;
        private Long expiresIn;
        private Boolean isNewUser;
        private Integer accountStatus;
        private Boolean needDisclaimer;
        /** 本次登录是否顺带撤销了注销申请（true 时前端需提示"账号已恢复"）。 */
        private Boolean recoveredFromCancellation;
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
