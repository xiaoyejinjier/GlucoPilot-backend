package com.threadfit.glucopilot.profile.health.constant;

/**
 * 健康档案与偏好设置的业务常量（值域边界、格式）。
 */
public final class ProfileHealthConstant {

    private ProfileHealthConstant() {
    }

    /** 确诊年月格式 YYYY-MM（对应 user_health.diagnosed_ym CHAR(7)，月校验 01–12）。 */
    public static final String DIAGNOSED_YM_PATTERN = "\\d{4}-(0[1-9]|1[0-2])";

    /** 年龄合法区间。 */
    public static final int AGE_MIN = 1;
    public static final int AGE_MAX = 150;

    /** 身高合法区间（cm）。 */
    public static final int HEIGHT_MIN = 50;
    public static final int HEIGHT_MAX = 250;

    /** 体重合法区间（kg，一位小数）。 */
    public static final String WEIGHT_MIN = "20.0";
    public static final String WEIGHT_MAX = "300.0";

    /** 目标血糖区间（mmol/L）。 */
    public static final String TARGET_MIN = "1.0";
    public static final String TARGET_MAX = "33.3";
}