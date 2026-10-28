package com.threadfit.glucopilot.user.settings;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.dto.UserDtos;
import com.threadfit.glucopilot.user.entity.UserSetting;
import com.threadfit.glucopilot.user.mapper.UserSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** C 端偏好设置：字号/主题/通知开关与免责声明同意。 */
@Service
@RequiredArgsConstructor
public class UserSettingService {

    /** 免责声明版本号在 sys_config 的键名，与 AppConfigService 的 TEXT 分组一致。 */
    public static final String DISCLAIMER_VERSION_KEY = "text.disclaimer.version";

    private final UserSettingMapper userSettingMapper;
    private final AppConfigService appConfigService;

    public UserDtos.SettingsView settingsOf(Long userId) {
        UserSetting setting = requireSetting(userId);
        return UserDtos.SettingsView.builder()
                .fontSize(setting.getFontSize())
                .themeCode(setting.getThemeCode())
                .notifyGlucose(toBoolean(setting.getNotifyGlucose()))
                .notifyMeal(toBoolean(setting.getNotifyMeal()))
                .notifyNews(toBoolean(setting.getNotifyNews()))
                .disclaimerAgreed(toBoolean(setting.getDisclaimerAgreed()))
                .disclaimerVersion(setting.getDisclaimerVersion())
                .disclaimerAgreedAt(setting.getDisclaimerAgreedAt())
                .build();
    }

    /** 部分更新：字段为 null 表示不改。 */
    @Transactional
    public UserDtos.UpdatedResult patchSettings(Long userId, UserDtos.SettingsPatchRequest request) {
        if (request.getFontSize() == null && request.getThemeCode() == null
                && request.getNotifyGlucose() == null && request.getNotifyMeal() == null
                && request.getNotifyNews() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少提交一个待修改字段");
        }
        UserSetting setting = requireSetting(userId);
        if (request.getFontSize() != null) {
            setting.setFontSize(request.getFontSize());
        }
        if (request.getThemeCode() != null) {
            setting.setThemeCode(request.getThemeCode());
        }
        if (request.getNotifyGlucose() != null) {
            setting.setNotifyGlucose(toFlag(request.getNotifyGlucose()));
        }
        if (request.getNotifyMeal() != null) {
            setting.setNotifyMeal(toFlag(request.getNotifyMeal()));
        }
        if (request.getNotifyNews() != null) {
            setting.setNotifyNews(toFlag(request.getNotifyNews()));
        }
        userSettingMapper.updateById(setting);
        return UserDtos.UpdatedResult.builder().updated(true).build();
    }

    /**
     * 同意免责声明。版本号必须与 sys_config 当前文案版本一致；
     * 运维尚未写入该参数时只记录客户端版本，不阻断用户。
     */
    @Transactional
    public UserDtos.DisclaimerAgreementResult agreeDisclaimer(
            Long userId, UserDtos.DisclaimerAgreementRequest request) {
        String current = appConfigService.textValue(DISCLAIMER_VERSION_KEY);
        if (current != null && !current.equals(request.getDisclaimerVersion())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "免责声明已更新，请重新阅读后再同意");
        }
        UserSetting setting = requireSetting(userId);
        setting.setDisclaimerAgreed(1);
        setting.setDisclaimerVersion(request.getDisclaimerVersion());
        setting.setDisclaimerAgreedAt(LocalDateTime.now());
        userSettingMapper.updateById(setting);
        return UserDtos.DisclaimerAgreementResult.builder().disclaimerAgreed(true).build();
    }

    /** 设置行由登录流程创建，缺失时兜底新建，避免历史数据导致 500。 */
    public UserSetting requireSetting(Long userId) {
        UserSetting setting = userSettingMapper.selectOne(new LambdaQueryWrapper<UserSetting>()
                .eq(UserSetting::getUserId, userId)
                .last("LIMIT 1"));
        if (setting != null) {
            return setting;
        }
        UserSetting created = new UserSetting();
        created.setUserId(userId);
        created.setFontSize(2);
        created.setThemeCode(1);
        created.setNotifyGlucose(1);
        created.setNotifyMeal(1);
        created.setNotifyNews(0);
        created.setDisclaimerAgreed(0);
        userSettingMapper.insert(created);
        return created;
    }

    /** 账号注销：物理删除偏好设置行（纯用户侧数据，无留存理由）。 */
    @Transactional
    public void purgeAccountData(Long userId) {
        userSettingMapper.purgeByUserId(userId);
    }

    static Integer toFlag(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    static Boolean toBoolean(Integer flag) {
        return flag != null && flag == 1;
    }
}
