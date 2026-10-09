package com.threadfit.glucopilot.profile.health.dto;

import com.threadfit.glucopilot.profile.health.constant.ProfileHealthConstant;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** 健康档案 DTO：2.8 全量保存请求与 /users/me 聚合视图。 */
public final class HealthDtos {

    private HealthDtos() {
    }

    /**
     * 全量保存请求（PUT /health-profile）。
     * null 语义：字段不传或传 null/空白，表示"清空该项恢复未填写"（服务端会显式置空）。
     * 校验：diabetesType ∈ [0,4]；diagnosedYm 须为 YYYY-MM 且不得晚于当前月(应用层 40001)；
     * targetFasting / targetPostprandial ∈ [1.0, 33.3] mmol/L；文本类字段带长度上限。
     */
    @Data
    public static class HealthSaveRequest {
        /** 糖尿病类型（CodeEnum.DiabetesType）：0未填/1一型/2二型/3妊娠/4其他；不传按未填写(0)处理。 */
        @Min(0)
        @Max(4)
        private Integer diabetesType;

        /** 兼容字段：展示文案由服务端按 diabetesType 派生，忽略客户端传入。 */
        @Size(max = 32)
        private String diabetesTypeText;

        /** YYYY-MM，不得晚于当前月。 */
        @Pattern(regexp = ProfileHealthConstant.DIAGNOSED_YM_PATTERN, message = "diagnosedYm 须为 YYYY-MM")
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

    /** 健康档案聚合视图，供 /users/me 幂等返回保存/读取结果。 */
    @Data
    @Builder
    public static class HealthView {
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
}
