package com.threadfit.glucopilot.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 管理端健康档案详情（GET /admin/v1/users/{id}/archive）。
     * 只读出参：owner/profile/mealSummary 取业务库真实数据；
     * height/weight 因 user_profile 无对应列暂返回 null，summary（AI 小结）待接入，均为 TODO。
     */
    @Data
    @Builder
    public static class Archive {
        private String userId;
        private Owner owner;
        private Profile profile;
        /** 近 7 日：按天均值与达标率（仅含有记录的天） */
        private List<TrendPoint> trend;
        /** 近 7 日：各血糖节点均值与样本数（节点名为字典值） */
        private List<NodeStat> nodeStats;
        private MealSummary mealSummary;
        /** AI 档案小结，暂无数据源，返回 null */
        private String summary;
    }

    @Data
    @Builder
    public static class Owner {
        private String nickname;
        /** 仅返回脱敏手机号 */
        private String phoneMasked;
        private Integer age;
        private String diabetesTypeText;
        private LocalDateTime registeredAt;
    }

    @Data
    @Builder
    public static class Profile {
        /** TODO user_profile 无身高列，暂 null */
        private Integer height;
        /** TODO user_profile 无体重列，暂 null */
        private Integer weight;
        private String medication;
        private String exerciseHabit;
        /** 确诊年月 YYYY-MM */
        private String diagnosedYm;
    }

    @Data
    @Builder
    public static class TrendPoint {
        private LocalDate date;
        private BigDecimal avg;
        /** 达标率（%），区间 [3.9, 7.8) */
        private Integer okRate;
    }

    @Data
    @Builder
    public static class NodeStat {
        private String node;
        private String nodeName;
        private BigDecimal avg;
        private Integer samples;
    }

    @Data
    @Builder
    public static class MealSummary {
        private Integer recordDays;
        private BigDecimal avgCarbsG;
        private BigDecimal avgCal;
        private List<String> topFoods;
    }
}
