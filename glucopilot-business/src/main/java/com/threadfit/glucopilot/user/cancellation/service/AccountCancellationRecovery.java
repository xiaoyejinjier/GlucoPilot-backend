package com.threadfit.glucopilot.user.cancellation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum.AccountStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.entity.UserCancelLogEntity;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserCancelLogMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录即恢复的编排。
 *
 * <h2>为什么单独成类</h2>
 *
 * 恢复需要读凭据表与账号表，但不能依赖 {@link com.threadfit.glucopilot.auth.service.AuthService}：
 * 登录链路要反向调用恢复，若恢复再依赖 AuthService 就会形成循环依赖。本类只依赖两个 Mapper，
 * AuthService 与注销服务都依赖它，依赖图保持无环。
 *
 * <h2>并发语义</h2>
 *
 * 必须在账号行锁内调用（{@code UserSessionMapper#lockAccount} 之后）：锁内重读账号决定"是否真的恢复"，
 * 并发第二台设备读到已恢复的账号时返回 {@code recovered=false}，按普通登录继续且不弹恢复提示。
 */
@Component
@RequiredArgsConstructor
public class AccountCancellationRecovery {

    private final UserAccountCancellationMapper cancellationMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserCancelLogMapper userCancelLogMapper;

    /** 恢复结果：{@code recovered=false} 表示锁内重读发现已被并发登录恢复（no-op）。 */
    public record RecoverOutcome(UserAccount account, boolean recovered) {
    }

    /**
     * 登录即恢复：账号处于可恢复窗口内则撤销注销并还原申请前状态。
     *
     * @param userId 已登录账号 ID
     * @return 恢复后的账号与"本次是否真的执行了恢复"
     */
    public RecoverOutcome recoverOnLogin(Long userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null
                || account.getAccountStatus() == null
                || account.getAccountStatus() != AccountStatus.CANCELLED_RECOVERABLE.getCode()) {
            return new RecoverOutcome(account, false);
        }
        UserAccountCancellation record = findActive(userId);
        if (record == null
                || record.getIsolateUntil() == null
                || !record.getIsolateUntil().isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.CONFLICT, "注销申请已过反悔期，账号正在清理中");
        }
        // 唯一裁判：worker 已把记录移出 PENDING（EXECUTED/RUNNING）时恢复必须失败，
        // 否则会出现"用户以为恢复了、数据其实正在被删"的错位。
        if (cancellationMapper.markRecovered(record.getId(), LocalDateTime.now()) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "注销正在处理中，无法恢复");
        }
        // 按申请前状态还原：DISABLED 恢复后仍是 DISABLED，不允许借"注销再恢复"洗白。
        int preStatus = record.getPreStatus() == null
                ? AccountStatus.NORMAL.getCode()
                : record.getPreStatus();
        account.setAccountStatus(preStatus);
        userAccountMapper.updateById(account);
        // 强审计：与恢复同事务；写失败则恢复回滚（登录失败可重试），避免"状态已改、审计缺失"
        userCancelLogMapper.insertIgnore(
                UserCancelLogEntity.builder()
                        .userId(userId)
                        .cancellationId(record.getId())
                        .actionType(UserCancelLogEntity.ACTION_USER_RECOVER)
                        .fromStatus(AccountStatus.CANCELLED_RECOVERABLE.getCode())
                        .toStatus(preStatus)
                        .operatorType(UserCancelLogEntity.OPERATOR_USER)
                        .operatorId(userId)
                        .requestId(RequestContext.getRequestId())
                        .clientIp(RequestContext.getClientIp())
                        .build());
        return new RecoverOutcome(account, true);
    }

    /** 当前活跃凭据（PENDING/RUNNING，RUNNING 仅历史僵尸行）；无则返回 null。 */
    public UserAccountCancellation findActive(Long userId) {
        List<UserAccountCancellation> list =
                cancellationMapper.selectList(
                        new LambdaQueryWrapper<UserAccountCancellation>()
                                .eq(UserAccountCancellation::getUserId, userId)
                                .in(
                                        UserAccountCancellation::getStatus,
                                        CancellationStatus.PENDING.getCode(),
                                        CancellationStatus.RUNNING.getCode())
                                .orderByDesc(UserAccountCancellation::getId)
                                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }
}
