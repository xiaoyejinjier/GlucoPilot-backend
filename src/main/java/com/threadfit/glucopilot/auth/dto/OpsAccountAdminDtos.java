package com.threadfit.glucopilot.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

public final class OpsAccountAdminDtos {

    private OpsAccountAdminDtos() {
    }

    @Data
    @Builder
    public static class AccountItem {
        private Long id;
        private String username;
        /** 不回传真实密码，固定空串以兼容前端类型 */
        private String password;
        private String nickname;
        /** admin | editor */
        private String role;
        /** 1 启用 / 0 停用 */
        private Integer status;
        private String createdAt;
        private String lastLoginAt;
    }

    @Data
    public static class CreateRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        private String nickname;
        /** admin | editor */
        @NotBlank
        private String role;
        @NotNull
        private Integer status;
    }

    @Data
    public static class UpdateRequest {
        private String password;
        private String nickname;
        /** admin | editor */
        private String role;
        private Integer status;
    }
}
