package com.threadfit.glucopilot.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        /** 仅user:manage接口返回绑定手机号，不进入公开社区资料。 */
        private String phone;
        private String phoneMasked;
        private Integer age;
        private String diabetesTypeText;
        private Integer accountStatus;
        private LocalDateTime registeredAt;
        private LocalDateTime lastActiveAt;
        private Long recordCount;
        private Long mealCount;
        private Integer complianceRate;

        /* ---------- 注销字段（仅 accountStatus=4 且存在活跃凭据时有值） ---------- */

        /** 注销申请时间（来自 user_account_cancellation.requested_at，仅用于展示）。 */
        private LocalDateTime cancelApplyTime;
        /** 可恢复截止时间 epoch ms（isolate_until）；窗口结束后登录/管理端均不可恢复。 */
        private Long cancelDeadlineAt;
        /** Worker 可清理时间 epoch ms（isolate_until + safety-margin）。 */
        private Long cancelPurgeEligibleAt;
        /** 当前是否仍在可恢复窗口内（服务端判定，避免客户端时钟漂移）。 */
        private Boolean cancelRecoverable;
        /** 是否已超过安全边际仍未被清理（前端红色提示依据）。 */
        private Boolean cancelPurgeOverdue;
        /** 恢复后的账号状态（凭据表 pre_status：0/1）。 */
        private Integer recoverToStatus;
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

    /** 管理端取消注销：备注选填，仅进审计，禁止放健康内容。 */
    @Data
    public static class RecoverRequest {
        @Size(max = 200, message = "备注不能超过 200 字")
        private String remark;
    }

    @Data
    @Builder
    public static class RecoverResult {
        private Boolean recovered;
        private Integer accountStatus;
        private LocalDateTime recoveredAt;
    }

    /**
     * 管理端健康档案详情（GET /admin/v1/users/{id}/archive）。
     * 只读出参：owner/profile/mealSummary 取业务库真实数据；
     * height/weight 取自 user_health（profile.health 域），summary（AI 小结）待接入，为 TODO。
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
        /** 身高 cm；来自 user_health.height_cm，未填写为 null */
        private Integer height;
        /** 体重 kg（一位小数）；来自 user_health.weight_kg，未填写为 null */
        private BigDecimal weight;
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
