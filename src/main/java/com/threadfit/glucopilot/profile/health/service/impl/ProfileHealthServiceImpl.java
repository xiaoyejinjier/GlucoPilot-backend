package com.threadfit.glucopilot.profile.health.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.auth.entity.UserAccount;
import com.threadfit.glucopilot.auth.mapper.UserAccountMapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.DiabetesType;
import com.threadfit.glucopilot.common.enums.CodeEnum.FontSize;
import com.threadfit.glucopilot.common.enums.CodeEnum.Gender;
import com.threadfit.glucopilot.common.enums.CodeEnum.ThemeCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.file.service.MediaFileService;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.DisclaimerAgreementRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthProfileSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.ProfileUpdateRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.SettingUpdateRequest;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import com.threadfit.glucopilot.profile.health.entity.UserProfile;
import com.threadfit.glucopilot.profile.health.entity.UserSetting;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import com.threadfit.glucopilot.profile.health.mapper.UserProfileMapper;
import com.threadfit.glucopilot.profile.health.mapper.UserSettingMapper;
import com.threadfit.glucopilot.profile.health.service.ProfileHealthService;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.AccountInfo;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.DisclaimerAgreementResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.HealthInfo;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.HealthSaveResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.ProfileInfo;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.ProfileUpdateResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.SettingInfo;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.SettingUpdateResult;
import com.threadfit.glucopilot.profile.health.vo.HealthVos.UserMe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class ProfileHealthServiceImpl implements ProfileHealthService {

    private static final DateTimeFormatter AGREED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserHealthMapper userHealthMapper;
    private final UserSettingMapper userSettingMapper;
    private final MediaFileService mediaFileService;

    @Override
    public UserMe getUserMe(Long userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        UserProfile profile = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, userId).last("LIMIT 1"));
        if (profile == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        UserHealth health = userHealthMapper.selectOne(new LambdaQueryWrapper<UserHealth>()
                .eq(UserHealth::getUserId, userId).last("LIMIT 1"));
        if (health == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        UserSetting setting = userSettingMapper.selectOne(new LambdaQueryWrapper<UserSetting>()
                .eq(UserSetting::getUserId, userId).last("LIMIT 1"));
        if (setting == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        return UserMe.builder()
                .account(toAccountInfo(account))
                .profile(toProfileInfo(profile))
                .health(toHealthInfo(health))
                .setting(toSettingInfo(setting))
                .build();
    }

    @Override
    @Transactional
    public ProfileUpdateResult updateProfile(ProfileUpdateRequest request, Long userId) {
        UserProfile existing = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, userId).last("LIMIT 1"));
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        // 非空字段走 entity（触发审计填充），avatarUrl 空串置空走 wrapper
        UserProfile entity = new UserProfile();
        entity.setId(existing.getId());
        LambdaUpdateWrapper<UserProfile> wrapper = new LambdaUpdateWrapper<UserProfile>()
                .eq(UserProfile::getId, existing.getId());

        boolean touched = false;
        if (request.getNickname() != null) {
            entity.setNickname(request.getNickname());
            touched = true;
        }
        if (request.getAvatarText() != null) {
            entity.setAvatarText(request.getAvatarText());
            touched = true;
        }
        if (request.getGender() != null) {
            CodeEnum.of(Gender.class, request.getGender());
            entity.setGender(request.getGender());
            touched = true;
        }
        if (request.getAvatarUrl() != null) {
            if (request.getAvatarUrl().isEmpty()) {
                wrapper.set(UserProfile::getAvatarUrl, null);
            } else {
                if (mediaFileService.findAvailableAvatar(userId, request.getAvatarUrl()) == null) {
                    throw new BizException(ErrorCode.BIZ_REJECTED, "头像不属于当前用户");
                }
                entity.setAvatarUrl(request.getAvatarUrl());
            }
            touched = true;
        }

        if (!touched) {
            return ProfileUpdateResult.builder().updated(false).profile(toProfileInfo(existing)).build();
        }
        userProfileMapper.update(entity, wrapper);
        UserProfile updated = userProfileMapper.selectById(existing.getId());
        return ProfileUpdateResult.builder().updated(true).profile(toProfileInfo(updated)).build();
    }

    @Override
    @Transactional
    public HealthSaveResult saveHealthProfile(HealthProfileSaveRequest request, Long userId) {
        UserHealth existing = userHealthMapper.selectOne(new LambdaQueryWrapper<UserHealth>()
                .eq(UserHealth::getUserId, userId).last("LIMIT 1"));
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        CodeEnum.of(DiabetesType.class, request.getDiabetesType());
        if (request.getDiagnosedYm() != null
                && YearMonth.parse(request.getDiagnosedYm()).isAfter(YearMonth.now())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "确诊年月不能晚于当前月");
        }

        // 全量保存：非 null 走 entity，null 走 wrapper.set(=NULL)；
        // update(entity, wrapper) 会对 entity 触发 AuditMetaObjectHandler 填充 gmt_modified/updated_by
        UserHealth entity = new UserHealth();
        entity.setId(existing.getId());
        LambdaUpdateWrapper<UserHealth> wrapper = new LambdaUpdateWrapper<UserHealth>()
                .eq(UserHealth::getId, existing.getId());

        entity.setDiabetesType(request.getDiabetesType());
        if (request.getDiabetesTypeText() != null) {
            entity.setDiabetesTypeText(request.getDiabetesTypeText());
        } else {
            wrapper.set(UserHealth::getDiabetesTypeText, null);
        }
        if (request.getDiagnosedYm() != null) {
            entity.setDiagnosedYm(request.getDiagnosedYm());
        } else {
            wrapper.set(UserHealth::getDiagnosedYm, null);
        }
        if (request.getAge() != null) {
            entity.setAge(request.getAge());
        } else {
            wrapper.set(UserHealth::getAge, null);
        }
        if (request.getMedication() != null) {
            entity.setMedication(request.getMedication());
        } else {
            wrapper.set(UserHealth::getMedication, null);
        }
        if (request.getExerciseHabit() != null) {
            entity.setExerciseHabit(request.getExerciseHabit());
        } else {
            wrapper.set(UserHealth::getExerciseHabit, null);
        }
        if (request.getTargetFasting() != null) {
            entity.setTargetFasting(request.getTargetFasting());
        } else {
            wrapper.set(UserHealth::getTargetFasting, null);
        }
        if (request.getTargetPostprandial() != null) {
            entity.setTargetPostprandial(request.getTargetPostprandial());
        } else {
            wrapper.set(UserHealth::getTargetPostprandial, null);
        }

        userHealthMapper.update(entity, wrapper);
        return HealthSaveResult.builder().id(String.valueOf(existing.getId())).build();
    }

    @Override
    @Transactional
    public SettingUpdateResult updateSettings(SettingUpdateRequest request, Long userId) {
        UserSetting existing = userSettingMapper.selectOne(new LambdaQueryWrapper<UserSetting>()
                .eq(UserSetting::getUserId, userId).last("LIMIT 1"));
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        UserSetting entity = new UserSetting();
        entity.setId(existing.getId());
        LambdaUpdateWrapper<UserSetting> wrapper = new LambdaUpdateWrapper<UserSetting>()
                .eq(UserSetting::getId, existing.getId());

        boolean touched = false;
        if (request.getFontSize() != null) {
            CodeEnum.of(FontSize.class, request.getFontSize());
            entity.setFontSize(request.getFontSize());
            touched = true;
        }
        if (request.getThemeCode() != null) {
            CodeEnum.of(ThemeCode.class, request.getThemeCode());
            entity.setThemeCode(request.getThemeCode());
            touched = true;
        }
        if (request.getNotifyGlucose() != null) {
            entity.setNotifyGlucose(toTinyInt(request.getNotifyGlucose()));
            touched = true;
        }
        if (request.getNotifyMeal() != null) {
            entity.setNotifyMeal(toTinyInt(request.getNotifyMeal()));
            touched = true;
        }
        if (request.getNotifyNews() != null) {
            entity.setNotifyNews(toTinyInt(request.getNotifyNews()));
            touched = true;
        }

        if (!touched) {
            return SettingUpdateResult.builder().updated(false).setting(toSettingInfo(existing)).build();
        }
        userSettingMapper.update(entity, wrapper);
        UserSetting updated = userSettingMapper.selectById(existing.getId());
        return SettingUpdateResult.builder().updated(true).setting(toSettingInfo(updated)).build();
    }

    @Override
    @Transactional
    public DisclaimerAgreementResult agreeDisclaimer(DisclaimerAgreementRequest request, Long userId) {
        UserSetting existing = userSettingMapper.selectOne(new LambdaQueryWrapper<UserSetting>()
                .eq(UserSetting::getUserId, userId).last("LIMIT 1"));
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();
        UserSetting entity = new UserSetting();
        entity.setId(existing.getId());
        entity.setDisclaimerAgreed(1);
        entity.setDisclaimerVersion(request.getDisclaimerVersion());
        entity.setDisclaimerAgreedAt(now);
        userSettingMapper.updateById(entity);

        return DisclaimerAgreementResult.builder()
                .disclaimerVersion(request.getDisclaimerVersion())
                .agreedAt(now.format(AGREED_AT_FORMAT))
                .build();
    }

    private static AccountInfo toAccountInfo(UserAccount account) {
        return AccountInfo.builder()
                .id(String.valueOf(account.getId()))
                .accountStatus(account.getAccountStatus())
                .phone(PhoneUtils.mask(account.getPhone()))
                .build();
    }

    private static ProfileInfo toProfileInfo(UserProfile profile) {
        return ProfileInfo.builder()
                .id(String.valueOf(profile.getId()))
                .nickname(profile.getNickname())
                .avatarUrl(profile.getAvatarUrl())
                .avatarText(profile.getAvatarText())
                .gender(profile.getGender())
                .build();
    }

    private static HealthInfo toHealthInfo(UserHealth health) {
        return HealthInfo.builder()
                .id(String.valueOf(health.getId()))
                .diabetesType(health.getDiabetesType())
                .diabetesTypeText(health.getDiabetesTypeText())
                .diagnosedYm(health.getDiagnosedYm())
                .age(health.getAge())
                .medication(health.getMedication())
                .exerciseHabit(health.getExerciseHabit())
                .targetFasting(health.getTargetFasting())
                .targetPostprandial(health.getTargetPostprandial())
                .build();
    }

    private static SettingInfo toSettingInfo(UserSetting setting) {
        return SettingInfo.builder()
                .id(String.valueOf(setting.getId()))
                .fontSize(setting.getFontSize())
                .themeCode(setting.getThemeCode())
                .notifyGlucose(toBool(setting.getNotifyGlucose()))
                .notifyMeal(toBool(setting.getNotifyMeal()))
                .notifyNews(toBool(setting.getNotifyNews()))
                .disclaimerAgreed(toBool(setting.getDisclaimerAgreed()))
                .build();
    }

    private static Integer toTinyInt(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private static Boolean toBool(Integer value) {
        return value != null && value == 1;
    }
}