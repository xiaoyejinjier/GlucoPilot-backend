package com.threadfit.glucopilot.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 账号注销 C 端接口 DTO。时间戳统一为 epoch 毫秒，序列化为字符串（对齐全局 Long 契约）。 */
public final class CancellationDtos {

    private CancellationDtos() {
    }

    @Data
    public static class SubmitRequest {
        /** 注销模式：1=立即注销 2=七天可恢复。 */
        @NotNull(message = "请选择注销模式")
        @Min(1)
        @Max(2)
        private Integer mode;

        /** 注销原因：1..4（可空）。 */
        @Min(1)
        @Max(4)
        private Integer reasonCode;

        @NotBlank(message = "请输入验证码")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        private String verifyCode;
    }

    @Data
    public static class RecoveryRequest {
        @NotBlank(message = "请输入验证码")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        private String verifyCode;
    }

    @Data
    @Builder
    public static class SendCodeView {
        /** 验证通道：1=短信 2=邮箱。 */
        private Integer channel;
        /** 脱敏后的收件方（如 138****0000）。 */
        private String maskedTarget;
        /** 验证码有效期（秒）。 */
        private Integer expiresIn;
        /** 发送冷却（秒）。 */
        private Integer cooldownSeconds;
    }

    @Data
    @Builder
    public static class CancellationView {
        private Integer mode;
        private Integer status;
        private Integer reasonCode;
        private Integer verifyChannel;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long requestedAt;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long isolateUntil;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long executedAt;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long recoveredAt;
    }

    @Data
    @Builder
    public static class QueryView {
        private Integer accountStatus;
        /** 无申请时为 null。 */
        private CancellationView cancellation;
    }

    @Data
    @Builder
    public static class SubmitView {
        private Integer accountStatus;
        private Integer mode;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long isolateUntil;
    }

    @Data
    @Builder
    public static class RecoveryView {
        private Integer accountStatus;
    }
}