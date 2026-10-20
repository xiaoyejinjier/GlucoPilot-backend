package com.threadfit.glucopilot.user.dto;

import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** C 端用户资料 / 偏好设置 DTO；健康档案 DTO 见 profile.health.dto.HealthDtos。 */
public final class UserDtos {

    private UserDtos() {
    }

    @Data
    @Builder
    public static class UpdatedResult {
        private Boolean updated;
    }

    @Data
    @Builder
    public static class ProfileView {
        private String nickname;
        private String avatarUrl;
        private String avatarText;
        /** 0未知1男2女 */
        private Integer gender;
    }

    @Data
    @Builder
    public static class SettingsView {
        private Integer fontSize;
        private Integer themeCode;
        private Boolean notifyGlucose;
        private Boolean notifyMeal;
        private Boolean notifyNews;
        private Boolean disclaimerAgreed;
        private String disclaimerVersion;
        private LocalDateTime disclaimerAgreedAt;
    }

    /** GET /users/me：账号 + 资料 + 健康档案 + 设置一次返回。 */
    @Data
    @Builder
    public static class MeResponse {
        private String communityKey;
        private Integer accountStatus;
        private String phoneMasked;
        private String emailMasked;
        private ProfileView profile;
        private HealthView health;
        private SettingsView settings;
    }

    /** 部分更新：字段为 null 表示不改。 */
    @Data
    public static class ProfilePatchRequest {
        @Size(max = 64)
        private String nickname;
        @Size(max = 512)
        private String avatarUrl;
        @Size(max = 8)
        private String avatarText;
        @Min(0)
        @Max(2)
        private Integer gender;
    }

    /** 部分更新：字段为 null 表示不改。 */
    @Data
    public static class SettingsPatchRequest {
        @Min(1)
        @Max(3)
        private Integer fontSize;
        @Min(1)
        @Max(3)
        private Integer themeCode;
        private Boolean notifyGlucose;
        private Boolean notifyMeal;
        private Boolean notifyNews;
    }

    @Data
    public static class DisclaimerAgreementRequest {
        @NotBlank
        @Size(max = 32)
        private String disclaimerVersion;
    }

    @Data
    @Builder
    public static class DisclaimerAgreementResult {
        private Boolean disclaimerAgreed;
    }

    @Data
    public static class PhoneBindRequest {
        @NotBlank
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        private String phone;
        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        private String verifyCode;
    }

    @Data
    @Builder
    public static class PhoneBindResult {
        private String phoneMasked;
    }

    @Data
    public static class EmailBindRequest {
        @NotBlank
        @Email(message = "邮箱格式不正确")
        @Size(max = 128)
        private String email;
        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        private String verifyCode;
    }

    @Data
    @Builder
    public static class EmailBindResult {
        private String emailMasked;
    }
}
