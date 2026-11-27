package com.threadfit.glucopilot.user.cancellation.service.impl;

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
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.user.cancellation.service.AccountCancellationService;
import com.threadfit.glucopilot.user.cancellation.service.CaptchaService;
import com.threadfit.glucopilot.user.cancellation.service.WalletCheckService;
import com.threadfit.glucopilot.user.dto.CancellationDtos.CancellationView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.CaptchaView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.PrecheckCheck;
import com.threadfit.glucopilot.user.dto.CancellationDtos.PrecheckView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.QueryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitView;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.entity.UserCancelLogEntity;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserCancelLogMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号注销。
 *
 * <p>验证通道按绑定关系自动选择：手机号 &gt; 邮箱 &gt; 微信（纯微信账号用 wx.login code 现场验证， 不再强制先绑定手机号/邮箱）。验证码场景固定为
 * {@code cancel}（与登录 login、绑定 bind 隔离消费）； 失败锁定沿用进程内内存（ConcurrentHashMap）方案，不引入
 * Redis，多实例部署时再评估。
 *
 * <h2>七天可恢复模式</h2>
 *
 * 提交后账号进入 {@code CANCELLED_RECOVERABLE(4)}，手机号/邮箱保持绑定；用户在 7 天窗口内
 * <b>正常登录即自动终止注销</b>（见 {@code AccountCancellationRecovery}），不再提供手动恢复接口。
 * 窗口结束（{@code isolateUntil}）后登录被拒绝，worker 在安全边际之后清理数据并落墓碑。
 *
 * <p>恢复后 24 小时内不可再次申请注销，起算点为 {@code recovered_at}。
 */
@Service
@RequiredArgsConstructor
public class AccountCancellationServiceImpl implements AccountCancellationService {

    private static final int RECOVERABLE_DAYS = 7;
    private static final long RECOVER_REAPPLY_LOCK_MS = 24L * 3600_000L;
    private static final int MAX_VERIFY_FAILURES = 5;

    /** 问诊进行中阻断原因码（precheck 第五项，独立于钱包四类资产）。 */
    private static final String CONSULTATION_IN_FLIGHT = "CONSULTATION_IN_FLIGHT";

    private final UserAccountMapper userAccountMapper;
    private final UserAccountCancellationMapper cancellationMapper;
    private final UserCancelLogMapper userCancelLogMapper;
    private final AuthService authService;
    private final CaptchaService captchaService;
    private final WalletCheckService walletCheckService;
    private final ConsultationMapper consultationMapper;

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
                .phoneMasked(
                        StringUtils.hasText(account.getPhone())
                                ? PhoneUtils.mask(account.getPhone())
                                : null)
                .emailMasked(
                        StringUtils.hasText(account.getEmail())
                                ? EmailUtils.mask(account.getEmail())
                                : null)
                .cancellation(latest == null ? null : toView(latest))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PrecheckView precheck(Long userId) {
        UserAccount account = requireAccount(userId);
        int status = account.getAccountStatus() == null ? -1 : account.getAccountStatus();

        UserAccountCancellation active = findActive(userId);
        LocalDateTime recoveredAt = latestRecoveredAt(userId);
        boolean recentlyRecovered = recoveredAt != null && isWithinReapplyLock(recoveredAt);
        WalletCheckService.WalletCheckResult wallet = walletCheckService.check(userId);
        boolean assetPassed = wallet.passed();
        String assetReason = wallet.reasonCode();
        boolean consultationInFlight = consultationMapper.countInFlight(userId) > 0;

        String blockCode = null;
        String blockMessage = null;
        String blockReasonCode = null;
        Long isolateUntil = null;
        if (status == AccountStatus.CANCELLING.getCode()) {
            blockCode = "CANCELLING";
            blockMessage = "账号注销处理中，无法重复申请";
            isolateUntil = active == null ? null : toMillis(active.getIsolateUntil());
        } else if (status == AccountStatus.CANCELLED.getCode()) {
            blockCode = "ACCOUNT_CANCELLED";
            blockMessage = "账号已注销，无法再次申请";
        } else if (active != null || status == AccountStatus.CANCELLED_RECOVERABLE.getCode()) {
            // 幂等优先于封禁：已提交的申请先返回现状，不因随后被封禁而报错
            blockCode = "ALREADY_APPLIED";
            blockMessage = "已有进行中的注销申请";
            isolateUntil = active == null ? null : toMillis(active.getIsolateUntil());
        } else if (status == AccountStatus.DISABLED.getCode()) {
            blockCode = "ACCOUNT_DISABLED";
            blockMessage = "账号已被封禁，请联系客服申请解冻后再注销";
        } else if (recentlyRecovered) {
            blockCode = "RECENTLY_RECOVERED";
            blockMessage = "账号恢复后 24 小时内不可再次注销，请稍后再试";
        } else if (!assetPassed) {
            blockCode = "ASSET_PENDING";
            blockMessage = walletBlockMessage(assetReason);
            blockReasonCode = assetReason;
        } else if (consultationInFlight) {
            blockCode = "ASSET_PENDING";
            blockMessage = "有进行中的接诊，请先完成或等待问诊结束";
            blockReasonCode = CONSULTATION_IN_FLIGHT;
        } else if (status != AccountStatus.NORMAL.getCode()) {
            blockCode = "ACCOUNT_UNUSABLE";
            blockMessage = "当前账号状态不可注销";
        }

        boolean noActiveApplication = active == null
                && status != AccountStatus.CANCELLING.getCode()
                && status != AccountStatus.CANCELLED_RECOVERABLE.getCode();
        List<PrecheckCheck> checks = new ArrayList<>(5);
        checks.add(
                PrecheckCheck.builder()
                        .code("ACCOUNT_SAFE")
                        .passed(status == AccountStatus.NORMAL.getCode())
                        .title("账号处于正常状态")
                        .detail(accountStatusDetail(status))
                        .build());
        checks.add(
                PrecheckCheck.builder()
                        .code("ASSET_CLEARED")
                        .passed(assetPassed)
                        .title("资产已结清")
                        .detail(assetPassed ? "无未提现余额或未结清订单" : "存在未提现余额或未结清订单")
                        .reasonCode(assetReason)
                        .build());
        checks.add(
                PrecheckCheck.builder()
                        .code("NO_ACTIVE_APPLICATION")
                        .passed(noActiveApplication)
                        .title("无进行中的注销申请")
                        .detail(noActiveApplication ? "当前没有待处理的注销申请" : "已有进行中的注销申请")
                        .build());
        checks.add(
                PrecheckCheck.builder()
                        .code("RECENTLY_RECOVERED")
                        .passed(!recentlyRecovered)
                        .title("已过重新提交间隔")
                        .detail(recentlyRecovered ? "距上次恢复不足 24 小时" : "距上次恢复已超过 24 小时")
                        .build());
        checks.add(
                PrecheckCheck.builder()
                        .code("CONSULTATION_IN_FLIGHT")
                        .passed(!consultationInFlight)
                        .title("无进行中的接诊")
                        .detail(consultationInFlight ? "有进行中的接诊，请先完成或等待问诊结束" : "无进行中的接诊")
                        .reasonCode(consultationInFlight ? CONSULTATION_IN_FLIGHT : null)
                        .build());

        return PrecheckView.builder()
                .canCancel(blockCode == null)
                .blockCode(blockCode)
                .blockMessage(blockMessage)
                .blockReasonCode(blockReasonCode)
                .isolateUntil(isolateUntil)
                .checks(checks)
                .build();
    }

    private static String accountStatusDetail(int status) {
        if (status == AccountStatus.NORMAL.getCode()) {
            return "账号状态正常，可办理注销";
        }
        if (status == AccountStatus.DISABLED.getCode()) {
            return "当前账号已被封禁";
        }
        if (status == AccountStatus.CANCELLING.getCode()) {
            return "账号注销处理中";
        }
        if (status == AccountStatus.CANCELLED.getCode()) {
            return "账号已注销";
        }
        if (status == AccountStatus.CANCELLED_RECOVERABLE.getCode()) {
            return "账号处于注销可恢复状态";
        }
        return "当前账号状态不可注销";
    }

    /** 钱包资产阻断原因码 → 用户可读文案（submit 409 message 与 precheck blockMessage 共用）。 */
    private static String walletBlockMessage(String reasonCode) {
        if (WalletCheckService.BALANCE.equals(reasonCode)) {
            return "钱包存在未提现余额，请先提现后再申请注销";
        }
        if (WalletCheckService.INCOME_PENDING.equals(reasonCode)) {
            return "存在未提现的问诊收入，请先提现后再申请注销";
        }
        if (WalletCheckService.WITHDRAWAL_IN_FLIGHT.equals(reasonCode)) {
            return "有正在处理的提现申请，请等待处理完成后再申请注销";
        }
        if (WalletCheckService.RECHARGE_IN_FLIGHT.equals(reasonCode)) {
            return "有未完成的充值订单，请等待订单关闭后再申请注销";
        }
        return "存在未结清资产，请先提现或结清后再申请注销";
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
        // 立即注销已下线：显式拒绝而不是静默改写，迫使前端同步下线该入口
        if (mode != CancellationMode.ISOLATE_7D.getCode()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持七天可恢复注销");
        }
        UserAccount account = requireAccount(userId);
        int status = account.getAccountStatus() == null ? -1 : account.getAccountStatus();

        // 已有处理中/可恢复申请 → 幂等返回现状
        UserAccountCancellation active = findActive(userId);
        if (active != null
                || status == AccountStatus.CANCELLING.getCode()
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
        // 封禁账号不允许自助注销（与 precheck 口径一致），需先联系客服解冻
        if (status == AccountStatus.DISABLED.getCode()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被封禁，请联系客服申请解冻后再注销");
        }
        if (status != AccountStatus.NORMAL.getCode()) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前账号状态不可注销");
        }
        rejectIfRecentlyRecovered(userId);

        // 验证方式由前端显式选择：1/2 必须是已绑定的手机号/邮箱，3 必须是本账号微信。
        int verifyType = request.getVerifyType();
        assertVerifyTarget(account, verifyType);

        // 钱包/资产校验必须在消费验证码之前：失败不消耗一次性凭证、不累计失败锁
        WalletCheckService.WalletCheckResult wallet = walletCheckService.check(userId);
        if (!wallet.passed()) {
            throw new BizException(ErrorCode.CONFLICT, walletBlockMessage(wallet.reasonCode()));
        }

        consumeVerifyCode(userId, verifyType, account, request);

        LocalDateTime now = LocalDateTime.now();
        UserAccountCancellation record = new UserAccountCancellation();
        record.setUserId(userId);
        record.setMode(CancellationMode.ISOLATE_7D.getCode());
        record.setStatus(CancellationStatus.PENDING.getCode());
        record.setReasonCode(request.getReasonCode());
        record.setVerifyChannel(verifyType);
        record.setPreStatus(status);
        record.setRequestedAt(now);
        record.setIsolateUntil(now.plusDays(RECOVERABLE_DAYS));
        record.setFailCount(0);
        cancellationMapper.insert(record);

        int newStatus = AccountStatus.CANCELLED_RECOVERABLE.getCode();
        account.setAccountStatus(newStatus);
        userAccountMapper.updateById(account);

        // 强审计：与凭据插入/账号更新同事务，写失败则整体回滚（注销申请必须可举证）
        userCancelLogMapper.insertIgnore(
                UserCancelLogEntity.builder()
                        .userId(userId)
                        .cancellationId(record.getId())
                        .actionType(UserCancelLogEntity.ACTION_APPLY)
                        .fromStatus(status)
                        .toStatus(newStatus)
                        .operatorType(UserCancelLogEntity.OPERATOR_USER)
                        .operatorId(userId)
                        .requestId(RequestContext.getRequestId())
                        .clientIp(RequestContext.getClientIp())
                        .build());

        // 提交成功即失效全部会话（登录态立即失效）
        authService.revokeAllSessions(userId);

        return SubmitView.builder()
                .accountStatus(newStatus)
                .mode(CancellationMode.ISOLATE_7D.getCode())
                .isolateUntil(toMillis(record.getIsolateUntil()))
                .build();
    }

    private UserAccount requireAccount(Long userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return account;
    }

    private UserAccountCancellation findActive(Long userId) {
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

    private UserAccountCancellation latest(Long userId) {
        List<UserAccountCancellation> list =
                cancellationMapper.selectList(
                        new LambdaQueryWrapper<UserAccountCancellation>()
                                .eq(UserAccountCancellation::getUserId, userId)
                                .orderByDesc(UserAccountCancellation::getId)
                                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private void rejectIfRecentlyRecovered(Long userId) {
        LocalDateTime recoveredAt = latestRecoveredAt(userId);
        if (recoveredAt != null && isWithinReapplyLock(recoveredAt)) {
            throw new BizException(ErrorCode.CONFLICT, "恢复后 24 小时内不可再次注销");
        }
    }

    /** 最近一次"已恢复"凭据的恢复时间，无则为 null（与 submit 的 24h 判定同源）。 */
    private LocalDateTime latestRecoveredAt(Long userId) {
        List<UserAccountCancellation> list =
                cancellationMapper.selectList(
                        new LambdaQueryWrapper<UserAccountCancellation>()
                                .eq(UserAccountCancellation::getUserId, userId)
                                .eq(
                                        UserAccountCancellation::getStatus,
                                        CancellationStatus.RECOVERED.getCode())
                                .orderByDesc(UserAccountCancellation::getRecoveredAt)
                                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0).getRecoveredAt();
    }

    private static boolean isWithinReapplyLock(LocalDateTime recoveredAt) {
        return recoveredAt.isAfter(
                LocalDateTime.now().minusSeconds(RECOVER_REAPPLY_LOCK_MS / 1000));
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
        if (verifyType == CancellationVerifyChannel.WECHAT.getCode() && !hasWechatBound(account)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前账号未绑定微信");
        }
    }

    /**
     * 是否已绑定真实微信。手机号/邮箱注册时 openid 写入 {@code phone_} / {@code email_} 占位值， 这类占位值不能当作微信凭证（也无法与
     * wx.login 换取的 openid 匹配）。
     */
    private static boolean hasWechatBound(UserAccount account) {
        String openid = account.getOpenid();
        return StringUtils.hasText(openid)
                && !openid.startsWith("phone_")
                && !openid.startsWith("email_");
    }

    private void consumeVerifyCode(
            Long userId, int verifyType, UserAccount account, SubmitRequest request) {
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
