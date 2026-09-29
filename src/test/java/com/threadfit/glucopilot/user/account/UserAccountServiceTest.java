package com.threadfit.glucopilot.user.account;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import com.threadfit.glucopilot.user.dto.UserDtos;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import com.threadfit.glucopilot.user.settings.UserSettingService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private UserSettingService userSettingService;
    @Mock
    private AuthService authService;
    @Mock
    private HealthProfileService healthProfileService;
    @Mock
    private MediaFileService mediaFileService;

    @InjectMocks
    private UserAccountService service;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 MyBatis 容器，LambdaUpdateWrapper 解析实体列需要手动注册 TableInfo
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserProfile.class);
    }

    @Test
    void meAggregatesAccountProfileHealthAndSettings() {
        when(userAccountMapper.selectById(USER_ID)).thenReturn(account());
        when(userProfileMapper.selectOne(any())).thenReturn(profile());
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(
                HealthView.builder().diabetesType(2).build());
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
        when(userProfileMapper.selectOne(any())).thenReturn(profile());

        UserDtos.ProfilePatchRequest request = new UserDtos.ProfilePatchRequest();
        request.setNickname("  吴先生  ");
        service.patchProfile(USER_ID, request);

        ArgumentCaptor<UserProfile> entityCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileMapper).update(entityCaptor.capture(), any(LambdaUpdateWrapper.class));
        UserProfile entity = entityCaptor.getValue();
        assertEquals(1L, entity.getId());
        assertEquals("吴先生", entity.getNickname());
        // 未提交的字段不进 SET，保持库中旧值
        assertNull(entity.getGender());
        assertNull(entity.getAvatarText());
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).updateById(accountCaptor.capture());
        assertEquals(USER_ID, accountCaptor.getValue().getId());
        assertEquals("吴先生", accountCaptor.getValue().getUsername());
    }

    @Test
    void patchProfileRejectsAvatarUrlFromOtherUser() {
        when(mediaFileService.findAvailableAvatar(USER_ID, "https://cdn.example.com/a.jpg"))
                .thenReturn(null);

        UserDtos.ProfilePatchRequest request = new UserDtos.ProfilePatchRequest();
        request.setAvatarUrl("https://cdn.example.com/a.jpg");

        assertThrows(BizException.class, () -> service.patchProfile(USER_ID, request));
    }

    @Test
    void patchProfileAcceptsOwnedAvatarUrl() {
        when(userProfileMapper.selectOne(any())).thenReturn(profile());
        when(mediaFileService.findAvailableAvatar(USER_ID, "https://cdn.example.com/a.jpg"))
                .thenReturn(new MediaFile());

        UserDtos.ProfilePatchRequest request = new UserDtos.ProfilePatchRequest();
        request.setAvatarUrl("https://cdn.example.com/a.jpg");
        service.patchProfile(USER_ID, request);

        ArgumentCaptor<UserProfile> entityCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileMapper).update(entityCaptor.capture(), any(LambdaUpdateWrapper.class));
        assertEquals("https://cdn.example.com/a.jpg", entityCaptor.getValue().getAvatarUrl());
    }

    @Test
    void patchProfileWithEmptyAvatarUrlClearsIt() {
        when(userProfileMapper.selectOne(any())).thenReturn(profile());

        UserDtos.ProfilePatchRequest request = new UserDtos.ProfilePatchRequest();
        request.setAvatarUrl("   ");
        service.patchProfile(USER_ID, request);

        // 空串清空：entity 不带该字段，由 wrapper 显式 SET NULL
        ArgumentCaptor<UserProfile> entityCaptor = ArgumentCaptor.forClass(UserProfile.class);
        ArgumentCaptor<LambdaUpdateWrapper<UserProfile>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userProfileMapper).update(entityCaptor.capture(), wrapperCaptor.capture());
        assertNull(entityCaptor.getValue().getAvatarUrl());
        assertTrue(wrapperCaptor.getValue().getSqlSet().contains("avatar_url"),
                "清空应生成 avatar_url 的 SET 子句");
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
}
