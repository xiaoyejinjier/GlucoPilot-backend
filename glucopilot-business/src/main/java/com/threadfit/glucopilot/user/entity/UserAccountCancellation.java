package com.threadfit.glucopilot.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 账号注销凭据（映射表 user_account_cancellation，见 V45）。
 *
 * <p>记录一次注销申请的申请/执行/恢复事实，是 PIPL 合规举证的载体；健康数据等敏感内容
 * 不入表，仅保留通道、模式、时间戳与失败摘要（last_error 不含任何健康内容）。
 *
 * <p>同一用户同时仅一条 PENDING/RUNNING（应用层保证）；恢复后可再次申请新行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_account_cancellation")
public class UserAccountCancellation extends BaseEntity {

    /** 用户账号 ID（关联 user_account.id）。 */
    private Long userId;

    /** 注销模式：1=立即注销 2=七天可恢复（CodeEnum.CancellationMode）。 */
    private Integer mode;

    /** 状态：0=待执行 1=执行中 2=已执行 3=已恢复 4=失败（CodeEnum.CancellationStatus）。 */
    private Integer status;

    /** 注销原因：1..4（可空，CodeEnum.CancellationReason）。 */
    private Integer reasonCode;

    /** 验证通道：1=短信 2=邮箱 3=微信（CodeEnum.CancellationVerifyChannel）。 */
    private Integer verifyChannel;

    /** 申请前账号状态：0=禁用 1=正常（登录取消注销时按此还原，见 V46）。 */
    private Integer preStatus;

    /** 申请时间。 */
    private LocalDateTime requestedAt;

    /** 可恢复截止时间（仅 mode=2 有值）。 */
    private LocalDateTime isolateUntil;

    /** 清理完成时间。 */
    private LocalDateTime executedAt;

    /** 恢复时间。 */
    private LocalDateTime recoveredAt;

    /** 失败重试次数。 */
    private Integer failCount;

    /** 最近失败原因（不含健康内容，长度受限）。 */
    private String lastError;
}