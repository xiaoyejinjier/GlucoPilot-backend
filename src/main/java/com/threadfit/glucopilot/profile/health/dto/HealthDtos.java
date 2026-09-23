package com.threadfit.glucopilot.profile.health.dto;

import com.threadfit.glucopilot.profile.health.constant.ProfileHealthConstant;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

public final class HealthDtos {

    private HealthDtos() {
    }

    /** 2.7 部分更新资料：字段非 null 才更新。 */
    @Data
    public static class ProfileUpdateRequest {
        @Size(max = 64)
        private String nickname;

        @Size(max = 512)
        private String avatarUrl;

        @Size(max = 8)
        private String avatarText;

        /** 性别（CodeEnum.Gender），非法值由 Service 校验。 */
        private Integer gender;
    }

    /** 2.8 全量保存健康档案：未传字段置空。 */
    @Data
    public static class HealthProfileSaveRequest {
        /** 糖尿病类型（CodeEnum.DiabetesType），必填。 */
        @NotNull
        private Integer diabetesType;

        @Size(max = 32)
        private String diabetesTypeText;

        @Pattern(regexp = ProfileHealthConstant.DIAGNOSED_YM_PATTERN)
        private String diagnosedYm;

        @Min(ProfileHealthConstant.AGE_MIN)
        @Max(ProfileHealthConstant.AGE_MAX)
        private Integer age;

        @Size(max = 256)
        private String medication;

        @Size(max = 256)
        private String exerciseHabit;

        @DecimalMin(ProfileHealthConstant.TARGET_MIN)
        @DecimalMax(ProfileHealthConstant.TARGET_MAX)
        private BigDecimal targetFasting;

        @DecimalMin(ProfileHealthConstant.TARGET_MIN)
        @DecimalMax(ProfileHealthConstant.TARGET_MAX)
        private BigDecimal targetPostprandial;
    }

    /** 2.9 部分更新偏好：字段非 null 才更新。 */
    @Data
    public static class SettingUpdateRequest {
        /** 字号（CodeEnum.FontSize），非法值由 Service 校验。 */
        private Integer fontSize;

        /** 主题（CodeEnum.ThemeCode），非法值由 Service 校验。 */
        private Integer themeCode;

        private Boolean notifyGlucose;

        private Boolean notifyMeal;

        private Boolean notifyNews;
    }

    /** 2.10 记录免责声明同意。 */
    @Data
    public static class DisclaimerAgreementRequest {
        @NotBlank
        @Size(max = 32)
        private String disclaimerVersion;
    }
}