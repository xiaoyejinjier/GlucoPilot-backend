package com.threadfit.glucopilot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.auth.client.WechatAuthClient;
import com.threadfit.glucopilot.auth.dto.AuthDtos;
import com.threadfit.glucopilot.auth.entity.AuthEmailCode;
import com.threadfit.glucopilot.auth.entity.AuthSmsCode;
import com.threadfit.glucopilot.auth.entity.UserSession;
import com.threadfit.glucopilot.auth.mail.EmailCodeMailSender;
import com.threadfit.glucopilot.auth.mapper.AuthEmailCodeMapper;
import com.threadfit.glucopilot.auth.mapper.AuthSmsCodeMapper;
import com.threadfit.glucopilot.auth.mapper.UserSessionMapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.EmailUtils;
import com.threadfit.glucopilot.common.util.IdUtils;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.entity.UserSetting;
import com.threadfit.glucopilot.user.cancellation.service.AccountCancellationRecovery;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import com.threadfit.glucopilot.user.mapper.UserSettingMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class AuthService {

    public static final long C_TOKEN_SECONDS = 7L * 24 * 3600;
    private static final int SMS_EXPIRE_SECONDS = 300;
    private static final long SMS_COOLDOWN_MS = 60_000L;
    private static final int EMAIL_EXPIRE_SECONDS = 300;
    private static final long EMAIL_COOLDOWN_MS = 60_000L;
    private static final long DAY_MS = 86_400_000L;

    private final WechatAuthClient wechatAuthClient;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserHealthMapper userHealthMapper;
    private final UserSettingMapper userSettingMapper;
    private final UserSessionMapper userSessionMapper;
    private final AuthSmsCodeMapper authSmsCodeMapper;
    private final AuthEmailCodeMapper authEmailCodeMapper;
    private final EmailCodeMailSender emailCodeMailSender;
    private final VerificationCodeConsumer verificationCodeConsumer;
    private final AccountCancellationRecovery cancellationRecovery;
    private final CancelRecoverTicketStore recoverTicketStore;

    /** 短信发送冷却：phone -> 上次发送时间戳（进程内内存，重启清空） */
    private final ConcurrentHashMap<String, AtomicLong> smsCooldown = new ConcurrentHashMap<>();

    /** 邮箱发送冷却：email -> 上次发送时间戳（进程内内存，重启清空） */
    private final ConcurrentHashMap<String, AtomicLong> emailCooldown = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    @Transactional
    public AuthDtos.SessionResponse loginByWechat(AuthDtos.WechatLoginRequest request) {
        WechatAuthClient.WechatIdentity identity = wechatAuthClient.exchangeCode(request.getCode());
        UserAccount account =
                userAccountMapper.selectOne(
                        new LambdaQueryWrapper<UserAccount>()
                                .eq(UserAccount::getOpenid, identity.openid())
                                .last("LIMIT 1"));
        boolean isNew = false;
        if (account == null) {
            account = createUserWithOpenid(identity.openid(), identity.unionId());
            isNew = true;
        }
        assertAccountLoginable(account, CodeEnum.LoginType.WECHAT.getCode());
        UserSession session = createSession(account.getId(), CodeEnum.LoginType.WECHAT.getCode());
        return finishLogin(account, session, isNew);
    }

    public AuthDtos.SmsCodeResponse sendSmsCode(AuthDtos.SmsCodeRequest request) {
        String scene = request.getScene().trim().toLowerCase();
        if (!"login".equals(scene)
                && !"bind".equals(scene)
                && !"cancel".equals(scene)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scene 仅支持 login/bind/cancel");
        }
        long now = System.currentTimeMillis();
        AtomicLong last = smsCooldown.computeIfAbsent(request.getPhone(), k -> new AtomicLong(0));
        long prev = last.get();
        if (prev > 0 && now - prev < SMS_COOLDOWN_MS) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "发送过于频繁，请稍后再试");
        }
        if (!last.compareAndSet(prev, now)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "发送过于频繁，请稍后再试");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        AuthSmsCode row = new AuthSmsCode();
        row.setPhone(request.getPhone());
        row.setVerifyCode(code);
        row.setScene(scene);
        row.setExpireAt(LocalDateTime.now().plusSeconds(SMS_EXPIRE_SECONDS));
        row.setIsUsed(0);
        row.setClientIp(RequestContext.getClientIp());
        authSmsCodeMapper.insert(row);

        // 开发环境：日志可见验证码；生产应走短信网关且禁止打码
        org.slf4j.LoggerFactory.getLogger(AuthService.class)
                .info("sms code phone={} scene={} code={}", request.getPhone(), scene, code);

        return AuthDtos.SmsCodeResponse.builder().expireIn(SMS_EXPIRE_SECONDS).build();
    }

    @Transactional
    public AuthDtos.SessionResponse loginByPhone(AuthDtos.PhoneLoginRequest request) {
        // 独立事务消费：后续任何回滚都不会让这条验证码"复活"
        verificationCodeConsumer.consumeForLoginBySms(request.getPhone(), request.getSmsCode());
        UserAccount account =
                userAccountMapper.selectOne(
                        new LambdaQueryWrapper<UserAccount>()
                                .eq(UserAccount::getPhone, request.getPhone())
                                .last("LIMIT 1"));
        boolean isNew = false;
        if (account == null) {
            // 手机号登录新建：openid 占位，后续可微信绑定
            account = createUserWithOpenid("phone_" + IdUtils.uuid().substring(0, 16), null);
            account.setPhone(request.getPhone());
            userAccountMapper.updateById(account);
            isNew = true;
        } else {
            assertAccountLoginable(account, CodeEnum.LoginType.PHONE.getCode());
        }
        UserSession session = createSession(account.getId(), CodeEnum.LoginType.PHONE.getCode());
        return finishLogin(account, session, isNew);
    }

    public AuthDtos.EmailCodeResponse sendEmailCode(AuthDtos.EmailCodeRequest request) {
        String scene = request.getScene().trim().toLowerCase();
        if (!"login".equals(scene)
                && !"bind".equals(scene)
                && !"cancel".equals(scene)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scene 仅支持 login/bind/cancel");
        }
        String email = EmailUtils.normalize(request.getEmail());
        long now = System.currentTimeMillis();
        AtomicLong last = emailCooldown.computeIfAbsent(email, k -> new AtomicLong(0));
        long prev = last.get();
        if (prev > 0 && now - prev < EMAIL_COOLDOWN_MS) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "发送过于频繁，请稍后再试");
        }
        if (!last.compareAndSet(prev, now)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "发送过于频繁，请稍后再试");
        }

        emailCodeMailSender.ensureConfigured();

        String code = String.format("%06d", random.nextInt(1_000_000));
        AuthEmailCode row = new AuthEmailCode();
        row.setEmail(email);
        row.setVerifyCode(code);
        row.setScene(scene);
        row.setExpireAt(LocalDateTime.now().plusSeconds(EMAIL_EXPIRE_SECONDS));
        row.setIsUsed(0);
        row.setClientIp(RequestContext.getClientIp());
        authEmailCodeMapper.insert(row);

        emailCodeMailSender.sendCodeAsync(email, code, () -> last.set(0));

        return AuthDtos.EmailCodeResponse.builder().expireIn(EMAIL_EXPIRE_SECONDS).build();
    }

    @Transactional
    public AuthDtos.SessionResponse loginByEmail(AuthDtos.EmailLoginRequest request) {
        String email = EmailUtils.normalize(request.getEmail());
        // 独立事务消费：后续任何回滚都不会让这条验证码"复活"
        verificationCodeConsumer.consumeForLoginByEmail(email, request.getEmailCode());
        UserAccount account =
                userAccountMapper.selectOne(
                        new LambdaQueryWrapper<UserAccount>()
                                .eq(UserAccount::getEmail, email)
                                .last("LIMIT 1"));
        boolean isNew = false;
        if (account == null) {
            account = createUserWithOpenid("email_" + IdUtils.uuid().substring(0, 16), null);
            account.setEmail(email);
            userAccountMapper.updateById(account);
            isNew = true;
        } else {
            assertAccountLoginable(account, CodeEnum.LoginType.EMAIL.getCode());
        }
        UserSession session = createSession(account.getId(), CodeEnum.LoginType.EMAIL.getCode());
        return finishLogin(account, session, isNew);
    }

    @Transactional
    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        UserSession session =
                userSessionMapper.selectOne(
                        new LambdaQueryWrapper<UserSession>()
                                .eq(UserSession::getAccessToken, accessToken)
                                .last("LIMIT 1"));
        if (session != null && session.getIsValid() != null && session.getIsValid() == 1) {
            session.setIsValid(0);
            userSessionMapper.updateById(session);
        }
    }

    public UserSession requireValidSession(String accessToken) {
        UserSession session = activeSession(accessToken);
        if (session == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        UserAccount account = userAccountMapper.selectById(session.getUserId());
        assertAccountUsable(account);
        return session;
    }

    /**
     * 解析 C 端会话：token 有效且账号非注销中/已注销时返回 userId + 账号状态； 否则返回 null（无效 / 注销中 / 已注销）。状态门禁由 {@code
     * UserSessionAuthFilter} 处理。
     */
    public AuthDtos.SessionPrincipal resolveSession(String accessToken) {
        UserSession session = activeSession(accessToken);
        if (session == null) {
            return null;
        }
        UserAccount account = userAccountMapper.selectById(session.getUserId());
        if (account == null) {
            return null;
        }
        Integer status = account.getAccountStatus();
        if (status != null
                && (status == CodeEnum.AccountStatus.CANCELLING.getCode()
                        || status == CodeEnum.AccountStatus.CANCELLED.getCode())) {
            return null;
        }
        return new AuthDtos.SessionPrincipal(session.getUserId(), status);
    }

    private UserSession activeSession(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        UserSession session =
                userSessionMapper.selectOne(
                        new LambdaQueryWrapper<UserSession>()
                                .eq(UserSession::getAccessToken, accessToken)
                                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (session == null
                || session.getExpireAt() == null
                || !session.getExpireAt().isAfter(now)) {
            return null;
        }
        if (!Integer.valueOf(1).equals(session.getIsValid())) {
            if (userSessionMapper.countOtherActiveSessions(session.getUserId(), accessToken, now)
                    > 0) {
                throw new BizException(ErrorCode.SESSION_REPLACED);
            }
            return null;
        }
        return session;
    }

    /** 注销/管理员操作时，批量失效某用户的全部有效会话。 */
    public void revokeAllSessions(Long userId) {
        userSessionMapper.update(
                null,
                new LambdaUpdateWrapper<UserSession>()
                        .eq(UserSession::getUserId, userId)
                        .eq(UserSession::getIsValid, 1)
                        .set(UserSession::getIsValid, 0));
    }

    /**
     * 账号注销：清理 auth 域数据。
     *
     * <p>手机号/邮箱必须由调用方在墓碑置空<b>之前</b>读出来传进来：验证码表没有 user_id， 墓碑一旦把 {@code phone/email}
     * 置空，这两张表就再也定位不到该用户的记录了。
     *
     * @param phone 注销前的手机号（可空）
     * @param email 注销前的邮箱（可空）
     */
    @Transactional
    public void purgeAccountData(Long userId, String phone, String email) {
        userSessionMapper.purgeByUserId(userId);
        if (phone != null && !phone.isBlank()) {
            authSmsCodeMapper.purgeByPhone(phone);
        }
        if (email != null && !email.isBlank()) {
            authEmailCodeMapper.purgeByEmail(email);
        }
    }

    /**
     * 绑定与注销提交消费验证码：沿用调用方事务（失败可回滚后重试），但用条件更新保证一次性。
     *
     * <p>登录路径请用 {@link VerificationCodeConsumer}：那里需要独立事务"失败即烧码"。
     */
    public void consumeSmsCode(String phone, String smsCode, String scene) {
        // 统一 40001，防枚举；截止时间用应用侧时间，避免 DB 会话时区差异
        if (authSmsCodeMapper.consume(phone, scene, smsCode, LocalDateTime.now()) != 1) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "验证码错误或已失效");
        }
    }

    /** 绑定与注销提交消费邮箱验证码，语义同 {@link #consumeSmsCode}。 */
    public void consumeEmailCode(String email, String emailCode, String scene) {
        String normalized = EmailUtils.normalize(email);
        if (authEmailCodeMapper.consume(normalized, scene, emailCode, LocalDateTime.now()) != 1) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "验证码错误或已失效");
        }
    }

    /** 注销等敏感操作的微信身份复核：现场用 wx.login code 换取 openid， 必须与账号 openid 一致。code 一次性有效，换取即消费，天然防重放。 */
    public void assertWechatIdentity(String code, String expectedOpenid) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先完成微信身份验证");
        }
        WechatAuthClient.WechatIdentity identity = wechatAuthClient.exchangeCode(code);
        if (identity == null
                || identity.openid() == null
                || !identity.openid().equals(expectedOpenid)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "微信身份验证失败，请重试");
        }
    }

    private UserAccount createUserWithOpenid(String openid, String unionId) {
        UserAccount account = new UserAccount();
        account.setUsername("用户_" + IdUtils.uuid().substring(0, 8));
        account.setOpenid(openid);
        account.setUnionId(unionId);
        account.setAccountStatus(CodeEnum.AccountStatus.NORMAL.getCode());
        userAccountMapper.insert(account);

        UserProfile profile = new UserProfile();
        profile.setUserId(account.getId());
        profile.setNickname(account.getUsername());
        profile.setGender(CodeEnum.Gender.UNKNOWN.getCode());
        userProfileMapper.insert(profile);

        UserHealth health = new UserHealth();
        health.setUserId(account.getId());
        health.setDiabetesType(CodeEnum.DiabetesType.UNSPECIFIED.getCode());
        userHealthMapper.insert(health);

        UserSetting setting = new UserSetting();
        setting.setUserId(account.getId());
        setting.setFontSize(2);
        setting.setThemeCode(1);
        setting.setNotifyGlucose(1);
        setting.setNotifyMeal(1);
        setting.setNotifyNews(0);
        setting.setDisclaimerAgreed(0);
        userSettingMapper.insert(setting);

        return account;
    }

    private UserSession createSession(Long userId, int loginType) {
        // 登录事务内先锁账号，再使旧令牌失效并插入新会话；并发登录也只保留最后一次签发。
        // 后续步骤失败会一起回滚，不会因为一次失败的登录踢掉原终端。
        if (userSessionMapper.lockAccount(userId) == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        revokeAllSessions(userId);
        UserSession session = new UserSession();
        session.setUserId(userId);
        session.setAccessToken(IdUtils.uuid());
        session.setLoginType(loginType);
        session.setExpireAt(LocalDateTime.now().plusSeconds(C_TOKEN_SECONDS));
        session.setClientIp(RequestContext.getClientIp());
        session.setUserAgent(RequestContext.getUserAgent());
        session.setIsValid(1);
        userSessionMapper.insert(session);
        return session;
    }

    private void touchLogin(UserAccount account) {
        account.setLastLoginAt(LocalDateTime.now());
        account.setLastLoginIp(RequestContext.getClientIp());
        userAccountMapper.updateById(account);
    }

    private void assertAccountUsable(UserAccount account) {
        if (account == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Integer status = account.getAccountStatus();
        if (status == null || status != CodeEnum.AccountStatus.NORMAL.getCode()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号不可用");
        }
    }

    /**
     * 登录放行：NORMAL / DISABLED 可登录；注销中(2) / 已注销(3) 拒绝。
     *
     * <p>状态 4（冷静期）不再静默恢复：只在窗口内签发一次性票据并返回
     * {@link ErrorCode#NEED_RECOVER_CONFIRM}，由用户在小程序二次确认后调用
     * {@link #confirmCancelRecovery(String)}。
     *
     * <p>窗口判定是"能否恢复"的权威依据：{@code now >= isolateUntil} 时即便 worker 还没开始清理，
     * 也一律拒绝；此处位于验证码消费之后，差异化文案只对被要求持码者可见。
     */
    private void assertAccountLoginable(UserAccount account, int loginType) {
        if (account == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Integer status = account.getAccountStatus();
        if (status == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号状态异常");
        }
        if (status == CodeEnum.AccountStatus.CANCELLING.getCode()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号注销处理中，无法登录");
        }
        if (status == CodeEnum.AccountStatus.CANCELLED.getCode()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已注销，无法登录");
        }
        if (status == CodeEnum.AccountStatus.CANCELLED_RECOVERABLE.getCode()) {
            UserAccountCancellation active = cancellationRecovery.findActive(account.getId());
            LocalDateTime now = LocalDateTime.now();
            if (active == null
                    || active.getIsolateUntil() == null
                    || !active.getIsolateUntil().isAfter(now)) {
                throw new BizException(ErrorCode.FORBIDDEN, "注销申请已过反悔期，账号正在清理中");
            }

            long leftMs = Duration.between(now, active.getIsolateUntil()).toMillis();
            String ticket = recoverTicketStore.issue(account.getId(), loginType);
            int preStatus = active.getPreStatus() == null
                    ? CodeEnum.AccountStatus.NORMAL.getCode()
                    : active.getPreStatus();

            throw new BizException(
                    ErrorCode.NEED_RECOVER_CONFIRM,
                    "账号处于注销冷静期，需确认是否恢复",
                    AuthDtos.CancelRecoverIntercept.builder()
                            .recoverRequired(true)
                            .recoverTicket(ticket)
                            .ticketExpireIn(CancelRecoverTicketStore.TTL_SECONDS)
                            .remainingDays((int) Math.max(1, (leftMs + DAY_MS - 1) / DAY_MS))
                            .cancelDeadlineAt(active.getIsolateUntil()
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                            .usernameMasked(PhoneUtils.maskUsername(account.getUsername()))
                            .phoneMasked(PhoneUtils.mask(account.getPhone()))
                            .recoverToStatus(preStatus)
                            .willStayDisabled(
                                    preStatus == CodeEnum.AccountStatus.DISABLED.getCode())
                            .build());
        }
    }

    /**
     * 确认恢复：用户在小程序点击「恢复账号并登录」后调用。
     *
     * <p>票据在凭证校验通过时签发，只能消费一次；恢复成功后签发正式 Token。
     * 账号行锁由 {@link #createSession} 获取，恢复核心在其后执行，保证与并发登录 /
     * 管理端恢复互斥。
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.SessionResponse confirmCancelRecovery(String ticket) {
        CancelRecoverTicketStore.Ticket parsed = recoverTicketStore.consume(ticket);
        if (parsed == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "恢复确认已失效，请重新登录");
        }

        UserSession session = createSession(parsed.userId(), parsed.loginType());

        AccountCancellationRecovery.RecoverOutcome outcome =
                cancellationRecovery.recoverOnConfirm(parsed.userId());
        UserAccount account = outcome.account();
        if (!outcome.recovered()) {
            int current = account == null ? -1 : account.getAccountStatus();
            // 已被另一台设备恢复：按幂等成功处理，正常签发本次会话
            if (current != CodeEnum.AccountStatus.NORMAL.getCode()
                    && current != CodeEnum.AccountStatus.DISABLED.getCode()) {
                throw new BizException(ErrorCode.CONFLICT, "账号状态已变化，请重新登录");
            }
        }

        touchLogin(account);
        return toSessionResponse(session, account, false, false);
    }

    /** 建会话后的收尾：走到这里账号只可能是 0/1，不再做任何注销恢复。 */
    private AuthDtos.SessionResponse finishLogin(
            UserAccount account, UserSession session, boolean isNew) {
        touchLogin(account);
        return toSessionResponse(session, account, isNew, false);
    }

    private AuthDtos.SessionResponse toSessionResponse(
            UserSession session, UserAccount account, boolean isNew, boolean recoveredFromCancellation) {
        UserSetting setting =
                userSettingMapper.selectOne(
                        new LambdaQueryWrapper<UserSetting>()
                                .eq(UserSetting::getUserId, account.getId())
                                .last("LIMIT 1"));
        boolean needDisclaimer =
                setting == null
                        || setting.getDisclaimerAgreed() == null
                        || setting.getDisclaimerAgreed() == 0;
        return AuthDtos.SessionResponse.builder()
                .accessToken(session.getAccessToken())
                .expiresIn(C_TOKEN_SECONDS)
                .isNewUser(isNew)
                .accountStatus(account.getAccountStatus())
                .needDisclaimer(needDisclaimer)
                .recoveredFromCancellation(recoveredFromCancellation)
                .build();
    }
}
