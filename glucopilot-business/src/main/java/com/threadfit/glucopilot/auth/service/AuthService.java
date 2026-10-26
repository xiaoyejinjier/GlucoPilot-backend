package com.threadfit.glucopilot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.auth.client.WechatAuthClient;
import com.threadfit.glucopilot.auth.dto.AuthDtos;
import com.threadfit.glucopilot.auth.entity.AuthEmailCode;
import com.threadfit.glucopilot.auth.entity.AuthSmsCode;
import com.threadfit.glucopilot.auth.mail.EmailCodeMailSender;
import com.threadfit.glucopilot.auth.entity.UserSession;
import com.threadfit.glucopilot.auth.mapper.AuthEmailCodeMapper;
import com.threadfit.glucopilot.auth.mapper.AuthSmsCodeMapper;
import com.threadfit.glucopilot.auth.mapper.UserSessionMapper;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.entity.UserSetting;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import com.threadfit.glucopilot.user.mapper.UserSettingMapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.EmailUtils;
import com.threadfit.glucopilot.common.util.IdUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
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

    private final WechatAuthClient wechatAuthClient;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserHealthMapper userHealthMapper;
    private final UserSettingMapper userSettingMapper;
    private final UserSessionMapper userSessionMapper;
    private final AuthSmsCodeMapper authSmsCodeMapper;
    private final AuthEmailCodeMapper authEmailCodeMapper;
    private final EmailCodeMailSender emailCodeMailSender;

    /** 短信发送冷却：phone -> 上次发送时间戳（进程内内存，重启清空） */
    private final ConcurrentHashMap<String, AtomicLong> smsCooldown = new ConcurrentHashMap<>();
    /** 邮箱发送冷却：email -> 上次发送时间戳（进程内内存，重启清空） */
    private final ConcurrentHashMap<String, AtomicLong> emailCooldown = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public AuthDtos.SessionResponse loginByWechat(AuthDtos.WechatLoginRequest request) {
        WechatAuthClient.WechatIdentity identity = wechatAuthClient.exchangeCode(request.getCode());
        UserAccount account = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getOpenid, identity.openid())
                .last("LIMIT 1"));
        boolean isNew = false;
        if (account == null) {
            account = createUserWithOpenid(identity.openid(), identity.unionId());
            isNew = true;
        }
        assertAccountLoginable(account);
        touchLogin(account);
        UserSession session = createSession(account.getId(), CodeEnum.LoginType.WECHAT.getCode());
        return toSessionResponse(session, account, isNew);
    }

    public AuthDtos.SmsCodeResponse sendSmsCode(AuthDtos.SmsCodeRequest request) {
        String scene = request.getScene().trim().toLowerCase();
        if (!"login".equals(scene) && !"bind".equals(scene) && !"cancel".equals(scene) && !"recover".equals(scene)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scene 仅支持 login/bind/cancel/recover");
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
        consumeSmsCode(request.getPhone(), request.getSmsCode());
        UserAccount account = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
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
            assertAccountLoginable(account);
        }
        touchLogin(account);
        UserSession session = createSession(account.getId(), CodeEnum.LoginType.PHONE.getCode());
        return toSessionResponse(session, account, isNew);
    }

    public AuthDtos.EmailCodeResponse sendEmailCode(AuthDtos.EmailCodeRequest request) {
        String scene = request.getScene().trim().toLowerCase();
        if (!"login".equals(scene) && !"bind".equals(scene) && !"cancel".equals(scene) && !"recover".equals(scene)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scene 仅支持 login/bind/cancel/recover");
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
        consumeEmailCode(email, request.getEmailCode());
        UserAccount account = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, email)
                .last("LIMIT 1"));
        boolean isNew = false;
        if (account == null) {
            account = createUserWithOpenid("email_" + IdUtils.uuid().substring(0, 16), null);
            account.setEmail(email);
            userAccountMapper.updateById(account);
            isNew = true;
        } else {
            assertAccountLoginable(account);
        }
        touchLogin(account);
        UserSession session = createSession(account.getId(), CodeEnum.LoginType.EMAIL.getCode());
        return toSessionResponse(session, account, isNew);
    }

    @Transactional
    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        UserSession session = userSessionMapper.selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getAccessToken, accessToken)
                .last("LIMIT 1"));
        if (session != null && session.getIsValid() != null && session.getIsValid() == 1) {
            session.setIsValid(0);
            userSessionMapper.updateById(session);
        }
    }

    public UserSession requireValidSession(String accessToken) {
        UserSession session = userSessionMapper.selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getAccessToken, accessToken)
                .eq(UserSession::getIsValid, 1)
                .last("LIMIT 1"));
        if (session == null || session.getExpireAt() == null || session.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        UserAccount account = userAccountMapper.selectById(session.getUserId());
        assertAccountUsable(account);
        return session;
    }

    /**
     * 解析 C 端会话：token 有效且账号非注销中/已注销时返回 userId + 账号状态；
     * 否则返回 null（无效 / 注销中 / 已注销）。状态门禁由 {@code UserSessionAuthFilter} 处理。
     */
    public AuthDtos.SessionPrincipal resolveSession(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        UserSession session = userSessionMapper.selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getAccessToken, accessToken)
                .eq(UserSession::getIsValid, 1)
                .last("LIMIT 1"));
        if (session == null || session.getExpireAt() == null
                || session.getExpireAt().isBefore(LocalDateTime.now())) {
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

    /** 注销/管理员操作时，批量失效某用户的全部有效会话。 */
    public void revokeAllSessions(Long userId) {
        userSessionMapper.update(null, new LambdaUpdateWrapper<UserSession>()
                .eq(UserSession::getUserId, userId)
                .eq(UserSession::getIsValid, 1)
                .set(UserSession::getIsValid, 0));
    }

    /**
     * 账号注销：清理 auth 域数据。
     * <p>
     * 手机号/邮箱必须由调用方在墓碑置空<b>之前</b>读出来传进来：验证码表没有 user_id，
     * 墓碑一旦把 {@code phone/email} 置空，这两张表就再也定位不到该用户的记录了。
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

    /** 登录与绑定手机号共用：校验并消费最近一条未使用验证码。 */
    public void consumeSmsCode(String phone, String smsCode) {
        AuthSmsCode row = authSmsCodeMapper.selectOne(new LambdaQueryWrapper<AuthSmsCode>()
                .eq(AuthSmsCode::getPhone, phone)
                .eq(AuthSmsCode::getIsUsed, 0)
                .orderByDesc(AuthSmsCode::getId)
                .last("LIMIT 1"));
        // 统一 40001，防枚举
        if (row == null
                || row.getExpireAt() == null
                || row.getExpireAt().isBefore(LocalDateTime.now())
                || !smsCode.equals(row.getVerifyCode())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "验证码错误或已失效");
        }
        row.setIsUsed(1);
        authSmsCodeMapper.updateById(row);
    }

    /** 登录与绑定邮箱共用：校验并消费最近一条未使用验证码。 */
    public void consumeEmailCode(String email, String emailCode) {
        String normalized = EmailUtils.normalize(email);
        AuthEmailCode row = authEmailCodeMapper.selectOne(new LambdaQueryWrapper<AuthEmailCode>()
                .eq(AuthEmailCode::getEmail, normalized)
                .eq(AuthEmailCode::getIsUsed, 0)
                .orderByDesc(AuthEmailCode::getId)
                .last("LIMIT 1"));
        if (row == null
                || row.getExpireAt() == null
                || row.getExpireAt().isBefore(LocalDateTime.now())
                || !emailCode.equals(row.getVerifyCode())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "验证码错误或已失效");
        }
        row.setIsUsed(1);
        authEmailCodeMapper.updateById(row);
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

    /** 登录放行：NORMAL / DISABLED / CANCELLED_RECOVERABLE 可登录（门禁另做限制），注销中/已注销拒绝。 */
    private void assertAccountLoginable(UserAccount account) {
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
    }

    private AuthDtos.SessionResponse toSessionResponse(UserSession session, UserAccount account, boolean isNew) {
        UserSetting setting = userSettingMapper.selectOne(new LambdaQueryWrapper<UserSetting>()
                .eq(UserSetting::getUserId, account.getId())
                .last("LIMIT 1"));
        boolean needDisclaimer = setting == null
                || setting.getDisclaimerAgreed() == null
                || setting.getDisclaimerAgreed() == 0;
        return AuthDtos.SessionResponse.builder()
                .accessToken(session.getAccessToken())
                .expiresIn(C_TOKEN_SECONDS)
                .isNewUser(isNew)
                .accountStatus(account.getAccountStatus())
                .needDisclaimer(needDisclaimer)
                .build();
    }
}
