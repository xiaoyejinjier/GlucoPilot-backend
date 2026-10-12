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
        @Pattern(regexp = ProfileHealthConstant.DIAGNOSED_YM_PATTERN, message = "确诊时间须为 YYYY-MM 格式（如 2021-06）")
        private String diagnosedYm;

        @Min(value = ProfileHealthConstant.AGE_MIN, message = "年龄不能小于 1 岁")
        @Max(value = ProfileHealthConstant.AGE_MAX, message = "年龄不能大于 150 岁")
        private Integer age;

        @Size(max = 256, message = "用药信息不能超过 256 个字符")
        private String medication;

        @Size(max = 256, message = "运动习惯不能超过 256 个字符")
        private String exerciseHabit;

        /** 身高 cm；null/空白 = 清空。 */
        @Min(value = ProfileHealthConstant.HEIGHT_MIN, message = "身高不能小于 50 cm")
        @Max(value = ProfileHealthConstant.HEIGHT_MAX, message = "身高不能大于 250 cm")
        private Integer height;

        /** 体重 kg（一位小数）；null = 清空。 */
        @DecimalMin(value = ProfileHealthConstant.WEIGHT_MIN, message = "体重不能小于 20.0 kg")
        @DecimalMax(value = ProfileHealthConstant.WEIGHT_MAX, message = "体重不能大于 300.0 kg")
        private BigDecimal weight;

        @DecimalMin(value = ProfileHealthConstant.TARGET_MIN, message = "空腹目标血糖不能低于 1.0 mmol/L")
        @DecimalMax(value = ProfileHealthConstant.TARGET_MAX, message = "空腹目标血糖不能高于 33.3 mmol/L")
        private BigDecimal targetFasting;

        @DecimalMin(value = ProfileHealthConstant.TARGET_MIN, message = "餐后目标血糖不能低于 1.0 mmol/L")
        @DecimalMax(value = ProfileHealthConstant.TARGET_MAX, message = "餐后目标血糖不能高于 33.3 mmol/L")
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
        private Integer height;
        private BigDecimal weight;
        private BigDecimal targetFasting;
        private BigDecimal targetPostprandial;
    }
}
