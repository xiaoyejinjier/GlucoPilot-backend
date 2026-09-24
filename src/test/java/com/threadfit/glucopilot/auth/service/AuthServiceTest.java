package com.threadfit.glucopilot.auth.service;

import com.threadfit.glucopilot.auth.client.WechatAuthClient;
import com.threadfit.glucopilot.auth.dto.AuthDtos;
import com.threadfit.glucopilot.auth.entity.AuthSmsCode;
import com.threadfit.glucopilot.auth.entity.UserSession;
import com.threadfit.glucopilot.auth.mapper.AuthSmsCodeMapper;
import com.threadfit.glucopilot.auth.mapper.UserSessionMapper;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.entity.UserSetting;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import com.threadfit.glucopilot.user.mapper.UserSettingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 合并回归：注册建档（account/profile/health/setting 四表）、二次登录、
 * 会话失效、验证码登录与 SMS 冷却。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String OPENID = "openid-1";

    @Mock
    private WechatAuthClient wechatAuthClient;
    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private UserProfileMapper userProfileMapper;
    @Mock
    private UserHealthMapper userHealthMapper;
    @Mock
    private UserSettingMapper userSettingMapper;
    @Mock
    private UserSessionMapper userSessionMapper;
    @Mock
    private AuthSmsCodeMapper authSmsCodeMapper;

    @InjectMocks
    private AuthService service;

    @Test
    void loginByWechatRegistersNewUserWithFourRows() {
        when(wechatAuthClient.exchangeCode("js-code")).thenReturn(
                new WechatAuthClient.WechatIdentity(OPENID, "union-1"));
        when(userAccountMapper.selectOne(any())).thenReturn(null);
        when(userSettingMapper.selectOne(any())).thenReturn(agreedSetting());

        AuthDtos.SessionResponse response =
                service.loginByWechat(wechatLogin("js-code"));

        assertTrue(response.getIsNewUser());
        assertTrue(response.getNeedDisclaimer());
        // 四表建档：账号 + 资料 + 健康（profile.health）+ 偏好
        verify(userAccountMapper).insert(any(UserAccount.class));
        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileMapper).insert(profileCaptor.capture());
        assertTrue(profileCaptor.getValue().getNickname().startsWith("用户_"), "昵称与用户名同步");
        ArgumentCaptor<UserHealth> healthCaptor = ArgumentCaptor.forClass(UserHealth.class);
        verify(userHealthMapper).insert(healthCaptor.capture());
        assertEquals(0, healthCaptor.getValue().getDiabetesType(), "新用户糖尿病类型为未填写");
        verify(userSettingMapper).insert(any(UserSetting.class));
        verify(userSessionMapper).insert(any(UserSession.class));
    }

    @Test
    void loginByWechatSecondTimeSkipsRegistration() {
        when(wechatAuthClient.exchangeCode("js-code")).thenReturn(
                new WechatAuthClient.WechatIdentity(OPENID, "union-1"));
        when(userAccountMapper.selectOne(any())).thenReturn(account(null));
        when(userSettingMapper.selectOne(any())).thenReturn(agreedSetting());

        AuthDtos.SessionResponse response =
                service.loginByWechat(wechatLogin("js-code"));

        assertEquals(false, response.getIsNewUser());
        // 已有账号不再重复建档，仅刷新登录信息
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
        verify(userProfileMapper, never()).insert(any(UserProfile.class));
        verify(userHealthMapper, never()).insert(any(UserHealth.class));
        verify(userSettingMapper, never()).insert(any(UserSetting.class));
        verify(userAccountMapper).updateById(any(UserAccount.class));
    }

    @Test
    void loginByPhoneWithValidCodeCreatesAccount() {
        when(authSmsCodeMapper.selectOne(any())).thenReturn(smsCode("123456", 300));
        when(userAccountMapper.selectOne(any())).thenReturn(null);
        when(userSettingMapper.selectOne(any())).thenReturn(agreedSetting());

        AuthDtos.SessionResponse response =
                service.loginByPhone(phoneLogin("13800000000", "123456"));

        assertTrue(response.getIsNewUser());
        // 验证码被消费
        ArgumentCaptor<AuthSmsCode> codeCaptor = ArgumentCaptor.forClass(AuthSmsCode.class);
        verify(authSmsCodeMapper).updateById(codeCaptor.capture());
        assertEquals(1, codeCaptor.getValue().getIsUsed());
        // 手机号登录建档：openid 占位 + 回填手机号
        verify(userAccountMapper).insert(any(UserAccount.class));
        verify(userProfileMapper).insert(any(UserProfile.class));
        verify(userHealthMapper).insert(any(UserHealth.class));
        verify(userSettingMapper).insert(any(UserSetting.class));
    }

    @Test
    void loginByPhoneRejectsWrongCode() {
        when(authSmsCodeMapper.selectOne(any())).thenReturn(smsCode("123456", 0));

        assertThrows(BizException.class, () ->
                service.loginByPhone(phoneLogin("13800000000", "000000")));

        verify(userAccountMapper, never()).insert(any(UserAccount.class));
        verify(userHealthMapper, never()).insert(any(UserHealth.class));
    }

    @Test
    void loginByPhoneRejectsExpiredCode() {
        when(authSmsCodeMapper.selectOne(any())).thenReturn(smsCode("123456", -300));

        assertThrows(BizException.class, () ->
                service.loginByPhone(phoneLogin("13800000000", "123456")));

        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void sendSmsCodeThrottlesWithinCooldown() {
        AuthDtos.SmsCodeRequest request = new AuthDtos.SmsCodeRequest();
        request.setPhone("13800000000");
        request.setScene("login");

        service.sendSmsCode(request);

        // 60 秒冷却窗口内二次发送被拒，且不重复落库
        assertThrows(BizException.class, () -> service.sendSmsCode(request));
        verify(authSmsCodeMapper).insert(any(AuthSmsCode.class));
    }

    @Test
    void sendSmsCodeRejectsUnknownScene() {
        AuthDtos.SmsCodeRequest request = new AuthDtos.SmsCodeRequest();
        request.setPhone("13800000000");
        request.setScene("reset");

        assertThrows(BizException.class, () -> service.sendSmsCode(request));
        verify(authSmsCodeMapper, never()).insert(any(AuthSmsCode.class));
    }

    @Test
    void logoutInvalidatesSession() {
        UserSession session = new UserSession();
        session.setId(1L);
        session.setAccessToken("token-1");
        session.setIsValid(1);
        when(userSessionMapper.selectOne(any())).thenReturn(session);

        service.logout("token-1");

        ArgumentCaptor<UserSession> sessionCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionMapper).updateById(sessionCaptor.capture());
        assertEquals(0, sessionCaptor.getValue().getIsValid(), "登出后会话置为无效");
    }

    @Test
    void logoutWithBlankTokenIsNoop() {
        service.logout("  ");

        verify(userSessionMapper, never()).selectOne(any());
    }

    @Test
    void requireValidSessionRejectsInvalidatedToken() {
        when(userSessionMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.requireValidSession("token-1"));
    }

    private static AuthDtos.WechatLoginRequest wechatLogin(String code) {
        AuthDtos.WechatLoginRequest request = new AuthDtos.WechatLoginRequest();
        request.setCode(code);
        return request;
    }

    private static AuthDtos.PhoneLoginRequest phoneLogin(String phone, String smsCode) {
        AuthDtos.PhoneLoginRequest request = new AuthDtos.PhoneLoginRequest();
        request.setPhone(phone);
        request.setSmsCode(smsCode);
        return request;
    }

    private static UserAccount account(Long id) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setOpenid(OPENID);
        account.setAccountStatus(1);
        return account;
    }

    private static UserSetting agreedSetting() {
        UserSetting setting = new UserSetting();
        setting.setUserId(1L);
        // 未免责 → needDisclaimer=true，覆盖响应组装分支
        setting.setDisclaimerAgreed(0);
        return setting;
    }

    private static AuthSmsCode smsCode(String code, int offsetSeconds) {
        AuthSmsCode row = new AuthSmsCode();
        row.setPhone("13800000000");
        row.setVerifyCode(code);
        row.setExpireAt(LocalDateTime.now().plusSeconds(offsetSeconds));
        row.setIsUsed(0);
        return row;
    }
}
