package com.threadfit.glucopilot.user.cancellation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.auth.dto.AuthDtos;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum.AccountStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationMode;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationVerifyChannel;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.EmailUtils;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.user.dto.CancellationDtos.CancellationView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.CaptchaView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.QueryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.RecoveryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeRequest;
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
 * <p>验证通道按绑定关系自动选择：手机号 &gt; 邮箱 &gt; 微信（纯微信账号用 wx.login code 现场验证，
 * 不再强制先绑定手机号/邮箱）。验证码场景 scene 由 AuthService 放行 {@code cancel}/{@code recover}；
 * 失败锁定沿用进程内内存（ConcurrentHashMap）方案，不引入 Redis，多实例部署时再评估。
 *
 * <p>七天可恢复模式的取消采用"登录即取消注销"：能建立会话本身就完成了本人身份核验
 * （wx.login code 换 openid / 短信/邮箱验证码登录），恢复不再二次验证；恢复后 24 小时内
 * 不可再次申请注销，并按申请前状态还原（DISABLED 仍是 DISABLED）。
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
    private final CaptchaService captchaService;

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
                .hasPhone(StringUtils.hasText(account.getPhone()))
                .hasEmail(StringUtils.hasText(account.getEmail()))
                .hasWechat(hasWechatBound(account))
                .phoneMasked(StringUtils.hasText(account.getPhone())
                        ? PhoneUtils.mask(account.getPhone()) : null)
                .emailMasked(StringUtils.hasText(account.getEmail())
                        ? EmailUtils.mask(account.getEmail()) : null)
                .cancellation(latest == null ? null : toView(latest))
                .build();
    }

    @Override
    public CaptchaView captcha(Long userId) {
        return captchaService.issue(userId, RequestContext.getClientIp());
    }

    @Override
    public SendCodeView sendCode(Long userId, SendCodeRequest request) {
        UserAccount account = requireAccount(userId);
        int channel = request.getChannel();
        String maskedTarget;
        if (channel == CancellationVerifyChannel.SMS.getCode()) {
            if (!StringUtils.hasText(account.getPhone())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "当前账号未绑定手机号");
            }
            maskedTarget = PhoneUtils.mask(account.getPhone());
        } else if (channel == CancellationVerifyChannel.EMAIL.getCode()) {
            if (!StringUtils.hasText(account.getEmail())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "当前账号未绑定邮箱");
            }
            maskedTarget = EmailUtils.mask(account.getEmail());
        } else {
            throw new BizException(ErrorCode.BAD_REQUEST, "验证方式仅支持短信或邮箱");
        }

        // 滑块只是 UX 门槛：服务端校验一次性票据（绑本人、180 秒）后才允许发码。
        captchaService.consume(request.getCaptchaTicket(), userId);
        captchaService.guardSendCode(userId, RequestContext.getClientIp());

        if (channel == CancellationVerifyChannel.SMS.getCode()) {
            AuthDtos.SmsCodeRequest sms = new AuthDtos.SmsCodeRequest();
            sms.setPhone(account.getPhone());
            sms.setScene("cancel");
            authService.sendSmsCode(sms);
        } else {
            AuthDtos.EmailCodeRequest email = new AuthDtos.EmailCodeRequest();
            email.setEmail(account.getEmail());
            email.setScene("cancel");
            authService.sendEmailCode(email);
        }
        return SendCodeView.builder()
                .channel(channel)
                .maskedTarget(maskedTarget)
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

        // 验证方式由前端显式选择：1/2 必须是已绑定的手机号/邮箱，3 必须是本账号微信。
        int verifyType = request.getVerifyType();
        assertVerifyTarget(account, verifyType);
        consumeVerifyCode(userId, verifyType, account, request);

        LocalDateTime now = LocalDateTime.now();
        boolean recoverable = mode == CancellationMode.ISOLATE_7D.getCode();
        UserAccountCancellation record = new UserAccountCancellation();
        record.setUserId(userId);
        record.setMode(mode);
        record.setStatus(CancellationStatus.PENDING.getCode());
        record.setReasonCode(request.getReasonCode());
        record.setVerifyChannel(verifyType);
        record.setPreStatus(status);
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
    public RecoveryView recover(Long userId) {
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

        // 登录即取消注销：能建立会话即完成本人身份核验（wx.login code / 短信/邮箱验证码登录），
        // 恢复不再二次验证。仍必须先 CAS 抢占凭据，再改账号状态：worker 可能刚把它抢成
        // RUNNING(1)，"恢复成功"与"数据正在被删"只能二选一。
        if (cancellationMapper.markRecovered(record.getId()) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "注销正在处理中，无法恢复");
        }
        // 按申请前状态还原：DISABLED 恢复后仍是 DISABLED，不允许借"注销再恢复"洗白。
        int preStatus = record.getPreStatus() == null
                ? AccountStatus.NORMAL.getCode()
                : record.getPreStatus();
        account.setAccountStatus(preStatus);
        userAccountMapper.updateById(account);

        return RecoveryView.builder().accountStatus(preStatus).build();
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

    /** 验证方式必须与实际绑定一致，避免把不可用的通道当成凭证。 */
    private void assertVerifyTarget(UserAccount account, int verifyType) {
        if (verifyType == CancellationVerifyChannel.SMS.getCode()
                && !StringUtils.hasText(account.getPhone())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前账号未绑定手机号");
        }
        if (verifyType == CancellationVerifyChannel.EMAIL.getCode()
                && !StringUtils.hasText(account.getEmail())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前账号未绑定邮箱");
        }
        if (verifyType == CancellationVerifyChannel.WECHAT.getCode()
                && !hasWechatBound(account)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前账号未绑定微信");
        }
    }

    /**
     * 是否已绑定真实微信。手机号/邮箱注册时 openid 写入 {@code phone_} / {@code email_} 占位值，
     * 这类占位值不能当作微信凭证（也无法与 wx.login 换取的 openid 匹配）。
     */
    private static boolean hasWechatBound(UserAccount account) {
        String openid = account.getOpenid();
        return StringUtils.hasText(openid)
                && !openid.startsWith("phone_")
                && !openid.startsWith("email_");
    }

    private void consumeVerifyCode(Long userId, int verifyType, UserAccount account, SubmitRequest request) {
        Long lockUntil = verifyLockUntil.get(userId);
        if (lockUntil != null && lockUntil > System.currentTimeMillis()) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "验证失败次数过多，请 24 小时后再试");
        }
        if (verifyType == CancellationVerifyChannel.WECHAT.getCode()) {
            if (!StringUtils.hasText(request.getWechatCode())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请先完成微信身份验证");
            }
        } else if (!StringUtils.hasText(request.getVerifyCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入验证码");
        }
        try {
            if (verifyType == CancellationVerifyChannel.WECHAT.getCode()) {
                // wx.login code 一次性有效，现场换取 openid 并与账号比对（换取即消费）。
                authService.assertWechatIdentity(request.getWechatCode(), account.getOpenid());
            } else if (verifyType == CancellationVerifyChannel.SMS.getCode()) {
                authService.consumeSmsCode(account.getPhone(), request.getVerifyCode(), "cancel");
            } else {
                authService.consumeEmailCode(account.getEmail(), request.getVerifyCode(), "cancel");
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