package com.threadfit.glucopilot.user.account;

import com.threadfit.glucopilot.ai.business.chat.service.ChatSessionService;
import com.threadfit.glucopilot.ai.business.ocr.service.OcrTaskService;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.community.CommunityService;
import com.threadfit.glucopilot.device.service.UserDeviceService;
import com.threadfit.glucopilot.export.DataExportService;
import com.threadfit.glucopilot.file.service.MediaFileService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.feedback.FeedbackService;
import com.threadfit.glucopilot.user.settings.UserSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排层的两个"顺序"契约：
 * <ol>
 *   <li>auth 清理必须拿到<b>墓碑置空之前</b>的 phone/email；</li>
 *   <li>墓碑/脱敏必须是最后一步，否则中途失败会丢掉验证码清理所需的归属线索。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AccountDataPurgerTest {

    @Mock private UserAccountService userAccountService;
    @Mock private AuthService authService;
    @Mock private GlucoseRecordService glucoseRecordService;
    @Mock private MealRecordService mealRecordService;
    @Mock private HealthProfileService healthProfileService;
    @Mock private ChatSessionService chatSessionService;
    @Mock private OcrTaskService ocrTaskService;
    @Mock private UserChatMemoryService userChatMemoryService;
    @Mock private MediaFileService mediaFileService;
    @Mock private UserSettingService userSettingService;
    @Mock private UserDeviceService userDeviceService;
    @Mock private FeedbackService feedbackService;
    @Mock private DataExportService dataExportService;
    @Mock private CommunityService communityService;

    @Test
    void passesPhoneAndEmailToAuthBeforeTombstoneAndFinishesWithAnonymize() {
        UserAccount account = new UserAccount();
        account.setId(9L);
        account.setPhone("13812345678");
        account.setEmail("a@b.com");
        when(userAccountService.requireAccount(9L)).thenReturn(account);

        AccountDataPurger purger = new AccountDataPurger(
                userAccountService, authService, glucoseRecordService, mealRecordService,
                healthProfileService, chatSessionService, ocrTaskService, userChatMemoryService,
                mediaFileService, userSettingService, userDeviceService, feedbackService,
                dataExportService, communityService);

        purger.purge(9L);

        verify(authService).purgeAccountData(9L, "13812345678", "a@b.com");
        verify(glucoseRecordService).purgeAccountData(9L);
        verify(mealRecordService).purgeAccountData(9L);
        verify(healthProfileService).purgeAccountData(9L);
        verify(chatSessionService).purgeAccountData(9L);
        verify(ocrTaskService).purgeAccountData(9L);
        verify(userChatMemoryService).purgeAccountData(9L);
        verify(mediaFileService).purgeAccountData(9L);
        verify(userSettingService).purgeAccountData(9L);
        verify(userDeviceService).purgeAccountData(9L);
        verify(feedbackService).purgeAccountData(9L);
        verify(dataExportService).purgeAccountData(9L);
        verify(communityService).purgeAccountData(9L);

        InOrder order = inOrder(authService, userAccountService);
        order.verify(authService).purgeAccountData(9L, "13812345678", "a@b.com");
        order.verify(userAccountService).anonymizeAccount(9L);
    }
}
