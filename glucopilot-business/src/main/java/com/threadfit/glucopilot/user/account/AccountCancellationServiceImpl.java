package com.threadfit.glucopilot.user.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.AccountStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationMode;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationVerifyChannel;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.EmailUtils;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.user.dto.CancellationDtos.CancellationView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.QueryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.RecoveryRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.RecoveryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitView;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号注销。
 *
 * <p>三条验证码场景 scene 由 AuthService 放行 {@code cancel}/{@code recover}；失败锁定沿用
 * AuthService 的进程内内存（ConcurrentHashMap）方案，不引入 Redis，后续如需多实例再评估。
 */
@Service
@RequiredArgsConstructor
public class AccountCancellationServiceImpl implements AccountCancellationService {

    private static final int RECOVERABLE_DAYS = 7;
    private static final long RECOVER_REAPPLY_LOCK_MS = 24L * 3600_000L;
    private static final int MAX_VERIFY_FAILURES = 5;

    private final UserAccountMapper userAccountMapper;
    private final UserAccountCancellationMapper cancellationMapper;
    private final AuthService authService;

    /** 验证失败计数（userId -> 累计失败次数）。 */
    private final ConcurrentHashMap<Long, Integer> verifyFailCount = new ConcurrentHashMap<>();
    /** 验证锁定截止时间（epoch 毫秒）。 */
    private final ConcurrentHashMap<Long, Long> verifyLockUntil = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public QueryView getStatus(Long userId) {
        UserAccount account = requireAccount(userId);
        UserAccountCancellation latest = latest(userId);
        return QueryView.builder()
                .accountStatus(account.getAccountStatus())
                .cancellation(latest == null ? null : toView(latest))
                .build();
    }

    @Override
    public SendCodeView sendCode(Long userId) {
        UserAccount account = requireAccount(userId);
        int channel = resolveChannelOrThrow(account);
        if (channel == CancellationVerifyChannel.SMS.getCode()) {
            authService.sendSmsCode(new com.threadfit.glucopilot.auth.dto.AuthDtos.SmsCodeRequest() {{
                setPhone(account.getPhone());
                setScene("cancel");
            }});
            return SendCodeView.builder()
                    .channel(channel)
                    .maskedTarget(PhoneUtils.mask(account.getPhone()))
                    .expiresIn(300)
                    .cooldownSeconds(60)
                    .build();
        }
        authService.sendEmailCode(new com.threadfit.glucopilot.auth.dto.AuthDtos.EmailCodeRequest() {{
            setEmail(account.getEmail());
            setScene("cancel");
        }});
        return SendCodeView.builder()
                .channel(channel)
                .maskedTarget(EmailUtils.mask(account.getEmail()))
                .expiresIn(300)
                .cooldownSeconds(60)
                .build();
    }

    @Override
    @Transactional
    public SubmitView submit(Long userId, SubmitRequest request) {
        int mode = request.getMode();
        UserAccount account = requireAccount(userId);
        int status = account.getAccountStatus() == null ? -1 : account.getAccountStatus();

        // 已有处理中/可恢复申请 → 幂等返回现状
        UserAccountCancellation active = findActive(userId);
        if (active != null || status == AccountStatus.CANCELLING.getCode()
                || status == AccountStatus.CANCELLED_RECOVERABLE.getCode()) {
            return SubmitView.builder()
                    .accountStatus(status)
                    .mode(active == null ? mode : active.getMode())
                    .isolateUntil(active == null ? null : toMillis(active.getIsolateUntil()))
                    .build();
        }
        if (status == AccountStatus.CANCELLED.getCode()) {
            throw new BizException(ErrorCode.CONFLICT, "账号已注销，无法再次申请");
        }
        if (status != AccountStatus.NORMAL.getCode() && status != AccountStatus.DISABLED.getCode()) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前账号状态不可注销");
        }
        rejectIfRecentlyRecovered(userId);

        int channel = resolveChannelOrThrow(account);
        consumeVerifyCode(userId, channel, account, request.getVerifyCode());

        LocalDateTime now = LocalDateTime.now();
        boolean recoverable = mode == CancellationMode.ISOLATE_7D.getCode();
        UserAccountCancellation record = new UserAccountCancellation();
        record.setUserId(userId);
        record.setMode(mode);
        record.setStatus(CancellationStatus.PENDING.getCode());
        record.setReasonCode(request.getReasonCode());
        record.setVerifyChannel(channel);
        record.setRequestedAt(now);
        record.setIsolateUntil(recoverable ? now.plusDays(RECOVERABLE_DAYS) : null);
        record.setFailCount(0);
        cancellationMapper.insert(record);

        int newStatus = recoverable
                ? AccountStatus.CANCELLED_RECOVERABLE.getCode()
                : AccountStatus.CANCELLING.getCode();
        account.setAccountStatus(newStatus);
        userAccountMapper.updateById(account);

        // 提交成功即失效全部会话（登录态立即失效）
        authService.revokeAllSessions(userId);

        return SubmitView.builder()
                .accountStatus(newStatus)
                .mode(mode)
                .isolateUntil(toMillis(record.getIsolateUntil()))
                .build();
    }

    @Override
    @Transactional
    public RecoveryView recover(Long userId, RecoveryRequest request) {
        UserAccount account = requireAccount(userId);
        if (account.getAccountStatus() == null
                || account.getAccountStatus() != AccountStatus.CANCELLED_RECOVERABLE.getCode()) {
            throw new BizException(ErrorCode.CONFLICT, "当前账号状态不可恢复");
        }
        UserAccountCancellation record = findActive(userId);
        LocalDateTime now = LocalDateTime.now();
        if (record == null || record.getIsolateUntil() == null
                || record.getIsolateUntil().isBefore(now)) {
            throw new BizException(ErrorCode.CONFLICT, "可恢复窗口已过，无法恢复");
        }
        int channel = record.getVerifyChannel() == null
                ? resolveChannelOrThrow(account)
                : record.getVerifyChannel();
        consumeVerifyCode(userId, channel, account, request.getVerifyCode());

        // 必须先 CAS 抢占凭据，再改账号状态：worker 可能刚把它抢成 RUNNING(1)，
        // 此时"恢复成功"与"数据正在被删"会同时成立，只有凭据 CAS 能把这个错位挡住。
        if (cancellationMapper.markRecovered(record.getId()) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "注销正在处理中，无法恢复");
        }
        account.setAccountStatus(AccountStatus.NORMAL.getCode());
        userAccountMapper.updateById(account);

        return RecoveryView.builder().accountStatus(AccountStatus.NORMAL.getCode()).build();
    }

    private UserAccount requireAccount(Long userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return account;
    }

    private UserAccountCancellation findActive(Long userId) {
        List<UserAccountCancellation> list = cancellationMapper.selectList(
                new LambdaQueryWrapper<UserAccountCancellation>()
                        .eq(UserAccountCancellation::getUserId, userId)
                        .in(UserAccountCancellation::getStatus,
                                CancellationStatus.PENDING.getCode(), CancellationStatus.RUNNING.getCode())
                        .orderByDesc(UserAccountCancellation::getId)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private UserAccountCancellation latest(Long userId) {
        List<UserAccountCancellation> list = cancellationMapper.selectList(
                new LambdaQueryWrapper<UserAccountCancellation>()
                        .eq(UserAccountCancellation::getUserId, userId)
                        .orderByDesc(UserAccountCancellation::getId)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private void rejectIfRecentlyRecovered(Long userId) {
        List<UserAccountCancellation> list = cancellationMapper.selectList(
                new LambdaQueryWrapper<UserAccountCancellation>()
                        .eq(UserAccountCancellation::getUserId, userId)
                        .eq(UserAccountCancellation::getStatus, CancellationStatus.RECOVERED.getCode())
                        .orderByDesc(UserAccountCancellation::getRecoveredAt)
                        .last("LIMIT 1"));
        if (list.isEmpty()) {
            return;
        }
        LocalDateTime recoveredAt = list.get(0).getRecoveredAt();
        if (recoveredAt != null && recoveredAt.isAfter(LocalDateTime.now().minusSeconds(24 * 3600))) {
            throw new BizException(ErrorCode.CONFLICT, "恢复后 24 小时内不可再次注销");
        }
    }

    private int resolveChannelOrThrow(UserAccount account) {
        if (StringUtils.hasText(account.getPhone())) {
            return CancellationVerifyChannel.SMS.getCode();
        }
        if (StringUtils.hasText(account.getEmail())) {
            return CancellationVerifyChannel.EMAIL.getCode();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "请先绑定手机号或邮箱后再注销");
    }

    private void consumeVerifyCode(Long userId, int channel, UserAccount account, String code) {
        Long lockUntil = verifyLockUntil.get(userId);
        if (lockUntil != null && lockUntil > System.currentTimeMillis()) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "验证失败次数过多，请 24 小时后再试");
        }
        try {
            if (channel == CancellationVerifyChannel.SMS.getCode()) {
                authService.consumeSmsCode(account.getPhone(), code);
            } else {
                authService.consumeEmailCode(account.getEmail(), code);
            }
            verifyFailCount.remove(userId);
            verifyLockUntil.remove(userId);
        } catch (BizException ex) {
            int failures = verifyFailCount.getOrDefault(userId, 0) + 1;
            verifyFailCount.put(userId, failures);
            if (failures >= MAX_VERIFY_FAILURES) {
                verifyLockUntil.put(userId, System.currentTimeMillis() + RECOVER_REAPPLY_LOCK_MS);
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "验证失败次数过多，请 24 小时后再试");
            }
            throw ex;
        }
    }

    private CancellationView toView(UserAccountCancellation record) {
        return CancellationView.builder()
                .mode(record.getMode())
                .status(record.getStatus())
                .reasonCode(record.getReasonCode())
                .verifyChannel(record.getVerifyChannel())
                .requestedAt(toMillis(record.getRequestedAt()))
                .isolateUntil(toMillis(record.getIsolateUntil()))
                .executedAt(toMillis(record.getExecutedAt()))
                .recoveredAt(toMillis(record.getRecoveredAt()))
                .build();
    }

    private static Long toMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}