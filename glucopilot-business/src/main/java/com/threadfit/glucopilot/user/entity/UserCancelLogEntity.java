package com.threadfit.glucopilot.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 账号注销全链路审计（映射表 user_cancel_logs，见 V95）。
 *
 * <p>只追加：APPLY / USER_RECOVER / ADMIN_RECOVER / SYSTEM_PURGE 四类事件各写一行，
 * {@code uk_ucl_cancel_action(cancellation_id, action_type)} 保证同一凭据的同一事件不会重复落账。
 *
 * <p>刻意不继承 {@code BaseEntity}：本表没有 is_deleted / gmt_modified 等可变字段，
 * 带上它们反而会让"只追加"的语义变得模糊。{@code remark} 禁止写入任何健康内容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_cancel_logs")
public class UserCancelLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户账号 ID（user_account.id）。 */
    private Long userId;

    /** 注销凭据 ID（user_account_cancellation.id）；APPLY 等事件必须带，唯一键才生效。 */
    private Long cancellationId;

    /** APPLY / USER_RECOVER / ADMIN_RECOVER / SYSTEM_PURGE。 */
    private String actionType;

    /** 变更前账号状态；未知为 null。 */
    private Integer fromStatus;

    /** 变更后账号状态；未知为 null。 */
    private Integer toStatus;

    /** USER / ADMIN / SYSTEM。 */
    private String operatorType;

    /** 用户 ID / 运营账号 ID / 0（系统）。 */
    private Long operatorId;

    /** 备注（仅审核备注或失败摘要，不含健康内容）。 */
    private String remark;

    /** X-Request-Id，便于与网关/应用日志串联。 */
    private String requestId;

    /** 客户端 IP。 */
    private String clientIp;

    /** 入库时间，默认 CURRENT_TIMESTAMP(3) 由数据库填充。 */
    private LocalDateTime createdAt;

    public static final String ACTION_APPLY = "APPLY";
    public static final String ACTION_USER_RECOVER = "USER_RECOVER";
    public static final String ACTION_ADMIN_RECOVER = "ADMIN_RECOVER";
    public static final String ACTION_SYSTEM_PURGE = "SYSTEM_PURGE";

    public static final String OPERATOR_USER = "USER";
    public static final String OPERATOR_ADMIN = "ADMIN";
    public static final String OPERATOR_SYSTEM = "SYSTEM";
}
