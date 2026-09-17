package com.threadfit.glucopilot.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

public final class OpsAuthDtos {

    private OpsAuthDtos() {
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }

    @Data
    @Builder
    public static class SessionResponse {
        private String accessToken;
        private Long expiresIn;
        private String username;
        private String nickname;
        /** admin | editor（来自 sys_ops_role：super_admin→admin，其余→editor） */
        private String role;
        private Long opsId;
    }
}
