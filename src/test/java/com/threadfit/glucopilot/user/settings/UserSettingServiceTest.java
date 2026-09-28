package com.threadfit.glucopilot.user.settings;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.dto.UserDtos;
import com.threadfit.glucopilot.user.entity.UserSetting;
import com.threadfit.glucopilot.user.mapper.UserSettingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSettingServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserSettingMapper userSettingMapper;
    @Mock
    private AppConfigService appConfigService;

    @InjectMocks
    private UserSettingService service;

    @Test
    void patchWithoutAnyFieldIsRejected() {
        assertThrows(BizException.class, () -> service.patchSettings(USER_ID, new UserDtos.SettingsPatchRequest()));
    }

    @Test
    void patchConvertsBooleansToFlags() {
        UserSetting setting = setting();
        when(userSettingMapper.selectOne(any())).thenReturn(setting);

        UserDtos.SettingsPatchRequest request = new UserDtos.SettingsPatchRequest();
        request.setNotifyNews(true);
        request.setNotifyMeal(false);
        service.patchSettings(USER_ID, request);

        assertEquals(1, setting.getNotifyNews());
        assertEquals(0, setting.getNotifyMeal());
        // 未提交的字段保持不变
        assertEquals(1, setting.getNotifyGlucose());
        verify(userSettingMapper).updateById(setting);
    }

    @Test
    void disclaimerVersionMismatchIsRejected() {
        when(appConfigService.textValue(UserSettingService.DISCLAIMER_VERSION_KEY)).thenReturn("2026.09.14");

        UserDtos.DisclaimerAgreementRequest request = new UserDtos.DisclaimerAgreementRequest();
        request.setDisclaimerVersion("2026.01.01");

        assertThrows(BizException.class, () -> service.agreeDisclaimer(USER_ID, request));
    }

    @Test
    void disclaimerAgreementRecordsVersionAndTimestamp() {
        when(appConfigService.textValue(UserSettingService.DISCLAIMER_VERSION_KEY)).thenReturn("2026.09.14");
        UserSetting setting = setting();
        when(userSettingMapper.selectOne(any())).thenReturn(setting);

        UserDtos.DisclaimerAgreementRequest request = new UserDtos.DisclaimerAgreementRequest();
        request.setDisclaimerVersion("2026.09.14");
        UserDtos.DisclaimerAgreementResult result = service.agreeDisclaimer(USER_ID, request);

        assertTrue(result.getDisclaimerAgreed());
        assertEquals(1, setting.getDisclaimerAgreed());
        assertEquals("2026.09.14", setting.getDisclaimerVersion());
        assertNotNull(setting.getDisclaimerAgreedAt());
    }

    @Test
    void agreementIsAcceptedWhenServerHasNoVersionConfigured() {
        when(appConfigService.textValue(UserSettingService.DISCLAIMER_VERSION_KEY)).thenReturn(null);
        UserSetting setting = setting();
        when(userSettingMapper.selectOne(any())).thenReturn(setting);

        UserDtos.DisclaimerAgreementRequest request = new UserDtos.DisclaimerAgreementRequest();
        request.setDisclaimerVersion("2026.09.14");
        UserDtos.DisclaimerAgreementResult result = service.agreeDisclaimer(USER_ID, request);

        assertTrue(result.getDisclaimerAgreed());
        assertEquals("2026.09.14", setting.getDisclaimerVersion());
        assertEquals(1, setting.getDisclaimerAgreed());
    }

    private static UserSetting setting() {
        UserSetting setting = new UserSetting();
        setting.setId(1L);
        setting.setUserId(USER_ID);
        setting.setFontSize(2);
        setting.setThemeCode(1);
        setting.setNotifyGlucose(1);
        setting.setNotifyMeal(1);
        setting.setNotifyNews(0);
        setting.setDisclaimerAgreed(0);
        return setting;
    }
}
