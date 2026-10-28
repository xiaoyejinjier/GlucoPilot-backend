package com.threadfit.glucopilot.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 账号注销 C 端接口 DTO。时间戳统一为 epoch 毫秒，序列化为字符串（对齐全局 Long 契约）。 */
public final class CancellationDtos {

    private CancellationDtos() {
    }

    @Data
    public static class SendCodeRequest {
        /** 验证通道：1=短信 2=邮箱（前端按用户所选显式传入，不再由服务端猜测）。 */
        @NotNull(message = "请选择验证方式")
        @Min(1)
        @Max(2)
        private Integer channel;

        /** 滑动验证一次性票据（GET /cancellation/captcha 获取，绑定 userId，180 秒有效）。 */
        @NotBlank(message = "请先完成滑动验证")
        @Size(max = 64, message = "滑动验证票据格式错误")
        private String captchaTicket;
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

        /** 验证方式：1=短信 2=邮箱 3=微信（必填，服务端按此校验对应凭证）。 */
        @NotNull(message = "请选择验证方式")
        @Min(1)
        @Max(3)
        private Integer verifyType;

        /** 短信/邮箱通道的 6 位验证码。 */
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        private String verifyCode;

        /** 微信通道的 wx.login code（code 一次性有效，换取即消费）。 */
        @Size(max = 128, message = "微信凭证格式错误")
        private String wechatCode;
    }

    @Data
    @Builder
    public static class CaptchaView {
        /** 一次性滑动验证票据，发码时回传。 */
        private String ticket;
        /** 票据有效期（秒）。 */
        private Integer expiresIn;
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
        /** 是否绑定手机号 / 邮箱 / 微信，前端据此决定展示哪些验证方式。 */
        private Boolean hasPhone;
        private Boolean hasEmail;
        private Boolean hasWechat;
        /** 脱敏后的绑定目标，用于“验证码将发送至 …”提示（未绑定为 null）。 */
        private String phoneMasked;
        private String emailMasked;
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