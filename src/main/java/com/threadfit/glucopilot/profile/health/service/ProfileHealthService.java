package com.threadfit.glucopilot.profile.health.service;

import com.threadfit.glucopilot.profile.health.dto.HealthDtos.DisclaimerAgreementRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthProfileSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.ProfileUpdateRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.SettingUpdateRequest;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.DisclaimerAgreementResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.HealthSaveResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.ProfileUpdateResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.SettingUpdateResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.UserMe;

public interface ProfileHealthService {

    /** 2.6 聚合账号 + 资料 + 健康 + 设置。 */
    UserMe getUserMe(Long userId);

    /** 2.7 部分更新资料。 */
    ProfileUpdateResult updateProfile(ProfileUpdateRequest request, Long userId);

    /** 2.8 全量保存健康档案。 */
    HealthSaveResult saveHealthProfile(HealthProfileSaveRequest request, Long userId);

    /** 2.9 部分更新偏好。 */
    SettingUpdateResult updateSettings(SettingUpdateRequest request, Long userId);

    /** 2.10 记录免责声明同意。 */
    DisclaimerAgreementResult agreeDisclaimer(DisclaimerAgreementRequest request, Long userId);
}