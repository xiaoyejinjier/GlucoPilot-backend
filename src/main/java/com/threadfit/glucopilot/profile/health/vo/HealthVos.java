package com.threadfit.glucopilot.profile.health.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

public final class HealthVos {

    private HealthVos() {
    }

    /** 2.6 聚合：账号 + 资料 + 健康 + 设置。 */
    @Data
    @Builder
    public static class UserMe {
        private AccountInfo account;
        private ProfileInfo profile;
        private HealthInfo health;
        private SettingInfo setting;
    }

    @Data
    @Builder
    public static class AccountInfo {
        private String id;
        private Integer accountStatus;
        /** 已脱敏（前 3 后 4）。 */
        private String phone;
    }

    @Data
    @Builder
    public static class ProfileInfo {
        private String id;
        private String nickname;
        private String avatarUrl;
        private String avatarText;
        private Integer gender;
    }

    @Data
    @Builder
    public static class HealthInfo {
        private String id;
        private Integer diabetesType;
        private String diabetesTypeText;
        private String diagnosedYm;
        private Integer age;
        private String medication;
        private String exerciseHabit;
        private BigDecimal targetFasting;
        private BigDecimal targetPostprandial;
    }

    @Data
    @Builder
    public static class SettingInfo {
        private String id;
        private Integer fontSize;
        private Integer themeCode;
        private Boolean notifyGlucose;
        private Boolean notifyMeal;
        private Boolean notifyNews;
        private Boolean disclaimerAgreed;
    }

    /** 2.7 出参。 */
    @Data
    @Builder
    public static class ProfileUpdateResult {
        private Boolean updated;
        private ProfileInfo profile;
    }

    /** 2.8 出参。 */
    @Data
    @Builder
    public static class HealthSaveResult {
        private String id;
    }

    /** 2.9 出参。 */
    @Data
    @Builder
    public static class SettingUpdateResult {
        private Boolean updated;
        private SettingInfo setting;
    }

    /** 2.10 出参。 */
    @Data
    @Builder
    public static class DisclaimerAgreementResult {
        private String disclaimerVersion;
        private String agreedAt;
    }
}