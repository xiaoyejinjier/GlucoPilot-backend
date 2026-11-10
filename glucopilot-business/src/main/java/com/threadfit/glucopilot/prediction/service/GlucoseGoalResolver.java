package com.threadfit.glucopilot.prediction.service;

import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * 血糖控制目标分层解析器（纯计算、无状态）。
 *
 * <p>三层来源（方案 §3.3）：
 * <pre>
 * glucoseGoalProfile = user_health.glucose_goal_profile   // PROFILE 层（档案人群档）
 * effectiveProfile   = (diabetesType==3 || profile==3) ? GDM : profile   // R6 取严
 * target             = profileDefaults[effectiveProfile]   // GENERAL 层（profile=0 即命中）
 *                    ?? GLOBAL 默认（非法档位兜底）        // GLOBAL 层
 * </pre>
 *
 * <p>目标分层仅用于状态判定与推荐约束，<b>不进收缩模型</b>（R1：收缩锚点恒为 7.8）。
 */
@Slf4j
public final class GlucoseGoalResolver {

    private GlucoseGoalResolver() {
    }

    /** 目标来源：PROFILE=人群档 / GENERAL=一般成人兜底(profile=0) / GLOBAL=全局默认(非法档位兜底)。 */
    public enum TargetSource { PROFILE, GENERAL, GLOBAL }

    /** 四类人群目标（targetLower / targetUpper，餐后 2h 口径）；数值见方案 §3.2，未标注者待临床确认。 */
    public enum Profile {
        GENERAL(0, "4.4", "10.0"),
        YOUNG_NO_COMPLICATION(1, "4.4", "7.8"),
        OLDER_HYPOGLYCEMIA(2, "5.0", "10.5"),
        GDM(3, "3.9", "6.7");

        private final int code;
        private final BigDecimal lower;
        private final BigDecimal upper;

        Profile(int code, String lower, String upper) {
            this.code = code;
            this.lower = new BigDecimal(lower);
            this.upper = new BigDecimal(upper);
        }

        public int code() {
            return code;
        }

        public BigDecimal lower() {
            return lower;
        }

        public BigDecimal upper() {
            return upper;
        }

        static Profile ofCode(int code) {
            for (Profile p : values()) {
                if (p.code == code) {
                    return p;
                }
            }
            return null;
        }
    }

    /** 解析结果：目标区间 + 来源 + 实际生效档（effectiveProfile 供审计，R14）。 */
    public record ResolvedGoal(BigDecimal targetLower, BigDecimal targetUpper, TargetSource source,
                               Profile effectiveProfile) {
    }

    /**
     * 解析生效目标。
     * <p>R6 取严：diabetesType=3 或 glucoseGoalProfile=3 任一为真即按 GDM（6.7），不修改用户档案数据；
     * 非法档位回落到 GLOBAL 默认目标并 WARN（正常链路不会触发，DB 默认 0 + 校验 0~3）。
     */
    public static ResolvedGoal resolve(HealthView health) {
        Integer gp = health.getGlucoseGoalProfile();
        int code = gp == null ? Profile.GENERAL.code : gp;
        boolean gdm = Integer.valueOf(Profile.GDM.code).equals(health.getDiabetesType())
                || code == Profile.GDM.code;
        Profile effective = gdm ? Profile.GDM : Profile.ofCode(code);
        if (effective == null) {
            log.warn("glucoseGoalProfile 非法档位 {}，回落 GLOBAL 默认目标", code);
            return new ResolvedGoal(Profile.GENERAL.lower(), Profile.GENERAL.upper(),
                    TargetSource.GLOBAL, Profile.GENERAL);
        }
        TargetSource source = effective == Profile.GENERAL ? TargetSource.GENERAL : TargetSource.PROFILE;
        return new ResolvedGoal(effective.lower(), effective.upper(), source, effective);
    }
}