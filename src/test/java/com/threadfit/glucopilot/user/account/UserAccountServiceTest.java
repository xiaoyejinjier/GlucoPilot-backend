package com.threadfit.glucopilot.user.account;

import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.dto.UserDtos;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserHealth;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import com.threadfit.glucopilot.user.settings.UserSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private UserProfileMapper userProfileMapper;
    @Mock
    private UserHealthMapper userHealthMapper;
    @Mock
    private UserSettingService userSettingService;
    @Mock
    private AuthService authService;

    @InjectMocks
    private UserAccountService service;

    @Test
    void meAggregatesAccountProfileHealthAndSettings() {
        when(userAccountMapper.selectById(USER_ID)).thenReturn(account());
        when(userProfileMapper.selectOne(any())).thenReturn(profile());
        when(userHealthMapper.selectOne(any())).thenReturn(health());
        when(userSettingService.settingsOf(USER_ID)).thenReturn(
                UserDtos.SettingsView.builder().fontSize(2).disclaimerAgreed(true).build());

        UserDtos.MeResponse me = service.me(USER_ID);

        assertEquals(1, me.getAccountStatus());
        assertEquals("138****0000", me.getPhoneMasked());
        assertEquals("吴先生", me.getProfile().getNickname());
        assertEquals(2, me.getHealth().getDiabetesType());
        assertEquals(true, me.getSettings().getDisclaimerAgreed());
    }

    @Test
    void patchProfileKeepsUntouchedFields() {
        UserProfile profile = profile();
        when(userProfileMapper.selectOne(any())).thenReturn(profile);

        UserDtos.ProfilePatchRequest request = new UserDtos.ProfilePatchRequest();
        request.setNickname("  吴先生  ");
        service.patchProfile(USER_ID, request);

        assertEquals("吴先生", profile.getNickname());
        // 未提交的字段不动
        assertEquals(1, profile.getGender());
        assertEquals("吴", profile.getAvatarText());
        verify(userProfileMapper).updateById(profile);
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).updateById(accountCaptor.capture());
        assertEquals(USER_ID, accountCaptor.getValue().getId());
        assertEquals("吴先生", accountCaptor.getValue().getUsername());
    }

    @Test
    void patchProfileWithEmptyAvatarUrlClearsIt() {
        UserProfile profile = profile();
        profile.setAvatarUrl("https://cdn.example.com/a.jpg");
        when(userProfileMapper.selectOne(any())).thenReturn(profile);

        UserDtos.ProfilePatchRequest request = new UserDtos.ProfilePatchRequest();
        request.setAvatarUrl("   ");
        service.patchProfile(USER_ID, request);

        assertNull(profile.getAvatarUrl());
    }

    @Test
    void patchProfileWithoutAnyFieldIsRejected() {
        assertThrows(BizException.class,
                () -> service.patchProfile(USER_ID, new UserDtos.ProfilePatchRequest()));
    }

    @Test
    void bindPhoneRejectsTakenNumberAfterCodeCheck() {
        when(userAccountMapper.selectById(USER_ID)).thenReturn(account());
        when(userAccountMapper.selectOne(any())).thenReturn(account());

        UserDtos.PhoneBindRequest request = new UserDtos.PhoneBindRequest();
        request.setPhone("13900000000");
        request.setVerifyCode("123456");

        assertThrows(BizException.class, () -> service.bindPhone(USER_ID, request));
        // 先校验验证码再判断占用，避免通过报错差异枚举已注册手机号
        verify(authService).consumeSmsCode("13900000000", "123456");
    }

    @Test
    void bindPhoneUpdatesAccount() {
        UserAccount account = account();
        when(userAccountMapper.selectById(USER_ID)).thenReturn(account);
        when(userAccountMapper.selectOne(any())).thenReturn(null);

        UserDtos.PhoneBindRequest request = new UserDtos.PhoneBindRequest();
        request.setPhone("13900000001");
        request.setVerifyCode("123456");
        UserDtos.PhoneBindResult result = service.bindPhone(USER_ID, request);

        assertEquals("139****0001", result.getPhoneMasked());
        assertEquals("13900000001", account.getPhone());
        verify(userAccountMapper).updateById(account);
        verify(authService).consumeSmsCode("13900000001", "123456");
    }

    private static UserAccount account() {
        UserAccount account = new UserAccount();
        account.setId(USER_ID);
        account.setUsername("吴先生");
        account.setPhone("13800000000");
        account.setAccountStatus(1);
        return account;
    }

    private static UserProfile profile() {
        UserProfile profile = new UserProfile();
        profile.setId(1L);
        profile.setUserId(USER_ID);
        profile.setNickname("吴先生");
        profile.setAvatarText("吴");
        profile.setGender(1);
        return profile;
    }

    private static UserHealth health() {
        UserHealth health = new UserHealth();
        health.setId(1L);
        health.setUserId(USER_ID);
        health.setDiabetesType(2);
        return health;
    }
}
