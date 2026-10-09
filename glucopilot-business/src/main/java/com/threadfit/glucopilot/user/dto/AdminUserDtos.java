package com.threadfit.glucopilot.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端 C 端用户查询与状态管理 DTO。 */
public final class AdminUserDtos {

    private AdminUserDtos() {
    }

    @Data
    @Builder
    public static class UserItem {
        private String id;
        private String username;
        private String nickname;
        private String avatarText;
        /** 只返回脱敏手机号。 */
        private String phoneMasked;
        private Integer age;
        private String diabetesTypeText;
        private Integer accountStatus;
        private LocalDateTime registeredAt;
        private LocalDateTime lastActiveAt;
        private Long recordCount;
        private Long mealCount;
        private Integer complianceRate;
    }

    @Data
    public static class StatusRequest {
        /** 用户管理页面仅允许禁用（0）或恢复正常（1）。 */
        @NotNull
        @Min(0)
        @Max(1)
        private Integer accountStatus;
    }

    @Data
    @Builder
    public static class UpdatedResult {
        private Boolean updated;
    }
}
