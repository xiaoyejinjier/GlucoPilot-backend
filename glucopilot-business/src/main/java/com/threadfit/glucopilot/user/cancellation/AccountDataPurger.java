package com.threadfit.glucopilot.user.cancellation;

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
import com.threadfit.glucopilot.user.account.UserAccountService;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.feedback.FeedbackService;
import com.threadfit.glucopilot.user.settings.UserSettingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号注销的清理编排。
 *
 * <h2>为什么这段逻辑不放在 worker 里</h2>
 * worker 的职责只有"扫描 + 抢占 + 记成功/失败"，它不该知道有哪些业务表。
 * 表清单会随业务演进变化，把它钉在定时任务里，以后每加一个模块都要改调度代码。
 *
 * <h2>事务边界：故意<b>不</b>加 {@code @Transactional}</h2>
 * 每一步都是下游 service 的独立事务。这样重试才有意义：第 8 步失败时，前 7 步已经提交，
 * 重试只需补剩下的，不会把几十万行删除又回滚一遍。代价是"中途失败会留下半删状态"，
 * 但这个状态对用户是安全的——账号已不可登录，且失败会被重试或升级为 FAILED 人工介入。
 *
 * <h2>顺序约束</h2>
 * <ul>
 *   <li>{@link UserAccountService#anonymizeAccount} 必须最后：它会清空 phone/email，
 *       而 auth 模块的验证码清理正是靠这两个值定位；</li>
 *   <li>饮食、对话、媒体这类"子表靠父表定位"的模块，删除顺序由各自 service 内部保证。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AccountDataPurger {

    private static final Logger log = LoggerFactory.getLogger(AccountDataPurger.class);

    private final UserAccountService userAccountService;
    private final AuthService authService;
    private final GlucoseRecordService glucoseRecordService;
    private final MealRecordService mealRecordService;
    private final HealthProfileService healthProfileService;
    private final ChatSessionService chatSessionService;
    private final OcrTaskService ocrTaskService;
    private final UserChatMemoryService userChatMemoryService;
    private final MediaFileService mediaFileService;
    private final UserSettingService userSettingService;
    private final UserDeviceService userDeviceService;
    private final FeedbackService feedbackService;
    private final DataExportService dataExportService;
    private final CommunityService communityService;

    /**
     * 执行一次全量清理。幂等：重复调用只会让已经是 0 行的删除再返回 0。
     *
     * @param userId 待清理用户
     */
    public void purge(long userId) {
        UserAccount account = userAccountService.requireAccount(userId);
        // 必须在任何 PII 置空之前读出：验证码表没有 user_id，只有 phone/email
        String phone = account.getPhone();
        String email = account.getEmail();

        authService.purgeAccountData(userId, phone, email);
        glucoseRecordService.purgeAccountData(userId);
        mealRecordService.purgeAccountData(userId);
        healthProfileService.purgeAccountData(userId);
        chatSessionService.purgeAccountData(userId);
        ocrTaskService.purgeAccountData(userId);
        userChatMemoryService.purgeAccountData(userId);
        mediaFileService.purgeAccountData(userId);
        userSettingService.purgeAccountData(userId);
        userDeviceService.purgeAccountData(userId);
        feedbackService.purgeAccountData(userId);
        dataExportService.purgeAccountData(userId);
        communityService.purgeAccountData(userId);

        // 墓碑 + 资料脱敏：清理链路的最后一步，此后该用户不再具备任何可识别信息
        userAccountService.anonymizeAccount(userId);
        log.info("账号注销清理完成 userId={}", userId);
    }
}
