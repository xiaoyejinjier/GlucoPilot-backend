package com.threadfit.glucopilot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.auth.client.WechatAuthClient;
import com.threadfit.glucopilot.auth.dto.AuthDtos;
import com.threadfit.glucopilot.auth.entity.AuthSmsCode;
import com.threadfit.glucopilot.auth.entity.UserSession;
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

    private final WechatAuthClient wechatAuthClient;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserHealthMapper userHealthMapper;
    private final UserSettingMapper userSettingMapper;
    private final UserSessionMapper userSessionMapper;
    private final AuthSmsCodeMapper authSmsCodeMapper;

    /** 短信发送冷却：phone -> 上次发送时间戳（进程内内存，重启清空） */
    private final ConcurrentHashMap<String, AtomicLong> smsCooldown = new ConcurrentHashMap<>();
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
        assertAccountUsable(account);
        touchLogin(account);
        UserSession session = createSession(account.getId(), CodeEnum.LoginType.WECHAT.getCode());
        return toSessionResponse(session, account, isNew);
    }

    public AuthDtos.SmsCodeResponse sendSmsCode(AuthDtos.SmsCodeRequest request) {
        String scene = request.getScene().trim().toLowerCase();
        if (!"login".equals(scene) && !"bind".equals(scene)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scene 仅支持 login/bind");
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
            assertAccountUsable(account);
        }
        touchLogin(account);
        UserSession session = createSession(account.getId(), CodeEnum.LoginType.PHONE.getCode());
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
