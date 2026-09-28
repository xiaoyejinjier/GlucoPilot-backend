package com.threadfit.glucopilot.user.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.Asserts;
import com.threadfit.glucopilot.user.dto.UserDtos;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserHealth;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import com.threadfit.glucopilot.user.settings.UserSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * C 端账号：聚合查询、资料部分更新、健康档案全量保存、手机号换绑。
 * 登录签发会话仍在 auth 模块，本服务只负责登录态下的资料读写。
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    /** 糖尿病类型文案，下标即 CodeEnum.DiabetesType 的枚举码；与前端 utils/mappers.js 对齐。 */
    private static final String[] DIABETES_TYPE_TEXT = {
            "未填写", "1 型糖尿病", "2 型糖尿病", "妊娠糖尿病", "其他"
    };

    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserHealthMapper userHealthMapper;
    private final UserSettingService userSettingService;
    private final AuthService authService;

    public UserDtos.MeResponse me(Long userId) {
        UserAccount account = requireAccount(userId);
        return UserDtos.MeResponse.builder()
                .accountStatus(account.getAccountStatus())
                .phoneMasked(maskPhone(account.getPhone()))
                .profile(toProfileView(requireProfile(userId)))
                .health(toHealthView(requireHealth(userId)))
                .settings(userSettingService.settingsOf(userId))
                .build();
    }

    /**
     * 部分更新资料。avatarUrl / avatarText 传空串表示清空、传 null 表示不改
     * （Jackson 无法区分「字段省略」与「显式 null」，故用空串表达清空）。
     */
    @Transactional
    public UserDtos.UpdatedResult patchProfile(Long userId, UserDtos.ProfilePatchRequest request) {
        if (request.getNickname() == null && request.getAvatarUrl() == null
                && request.getAvatarText() == null && request.getGender() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少提交一个待修改字段");
        }
        UserProfile profile = requireProfile(userId);
        if (request.getNickname() != null) {
            Asserts.notBlank(request.getNickname(), ErrorCode.BAD_REQUEST, "昵称不能为空");
            String username = request.getNickname().trim();
            profile.setNickname(username);
            // user_account.username 是账号侧用户名，与资料昵称保持同步。
            UserAccount accountPatch = new UserAccount();
            accountPatch.setId(userId);
            accountPatch.setUsername(username);
            userAccountMapper.updateById(accountPatch);
        }
        if (request.getAvatarUrl() != null) {
            profile.setAvatarUrl(nullIfBlank(request.getAvatarUrl()));
        }
        if (request.getAvatarText() != null) {
            profile.setAvatarText(nullIfBlank(request.getAvatarText()));
        }
        if (request.getGender() != null) {
            profile.setGender(request.getGender());
        }
        userProfileMapper.updateById(profile);
        return UserDtos.UpdatedResult.builder().updated(true).build();
    }

    /** 全量保存健康档案；小于 0 的目标值按未填处理。 */
    @Transactional
    public UserDtos.HealthView saveHealth(Long userId, UserDtos.HealthSaveRequest request) {
        UserHealth health = requireHealth(userId);
        int diabetesType = request.getDiabetesType() == null ? 0 : request.getDiabetesType();
        health.setDiabetesType(diabetesType);
        // 派生写入，不用请求里的自由文本：否则前端不传时 updateById 会跳过 null 字段，
        // 这一列就永远停在旧值，和 diabetes_type 不一致。
        health.setDiabetesTypeText(diabetesTypeTextOf(diabetesType));
        health.setDiagnosedYm(nullIfBlank(request.getDiagnosedYm()));
        health.setAge(request.getAge());
        health.setMedication(nullIfBlank(request.getMedication()));
        health.setExerciseHabit(nullIfBlank(request.getExerciseHabit()));
        health.setTargetFasting(request.getTargetFasting());
        health.setTargetPostprandial(request.getTargetPostprandial());
        userHealthMapper.updateById(health);
        return toHealthView(health);
    }

    /** 换绑手机号：先验验证码（避免账号枚举），再查占用。 */
    @Transactional
    public UserDtos.PhoneBindResult bindPhone(Long userId, UserDtos.PhoneBindRequest request) {
        authService.consumeSmsCode(request.getPhone(), request.getVerifyCode());

        UserAccount account = requireAccount(userId);
        if (request.getPhone().equals(account.getPhone())) {
            return UserDtos.PhoneBindResult.builder().phoneMasked(maskPhone(account.getPhone())).build();
        }
        UserAccount occupied = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getPhone, request.getPhone())
                .last("LIMIT 1"));
        if (occupied != null) {
            throw new BizException(ErrorCode.CONFLICT, "该手机号已被其他账号绑定");
        }
        account.setPhone(request.getPhone());
        userAccountMapper.updateById(account);
        return UserDtos.PhoneBindResult.builder().phoneMasked(maskPhone(account.getPhone())).build();
    }

    public UserAccount requireAccount(Long userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        return account;
    }

    /** 资料行由登录流程创建，缺失时兜底新建，避免历史数据导致 500。 */
    public UserProfile requireProfile(Long userId) {
        UserProfile profile = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, userId)
                .last("LIMIT 1"));
        if (profile != null) {
            return profile;
        }
        UserProfile created = new UserProfile();
        created.setUserId(userId);
        created.setNickname("");
        created.setGender(0);
        userProfileMapper.insert(created);
        return created;
    }

    public UserHealth requireHealth(Long userId) {
        UserHealth health = userHealthMapper.selectOne(new LambdaQueryWrapper<UserHealth>()
                .eq(UserHealth::getUserId, userId)
                .last("LIMIT 1"));
        if (health != null) {
            return health;
        }
        UserHealth created = new UserHealth();
        created.setUserId(userId);
        created.setDiabetesType(0);
        userHealthMapper.insert(created);
        return created;
    }

    /** 138****0000；长度不足时整体打码。 */
    public static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        if (phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String nullIfBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String diabetesTypeTextOf(int code) {
        return code >= 0 && code < DIABETES_TYPE_TEXT.length ? DIABETES_TYPE_TEXT[code] : null;
    }

    private static UserDtos.ProfileView toProfileView(UserProfile p) {
        return UserDtos.ProfileView.builder()
                .nickname(p.getNickname())
                .avatarUrl(p.getAvatarUrl())
                .avatarText(p.getAvatarText())
                .gender(p.getGender())
                .build();
    }

    private static UserDtos.HealthView toHealthView(UserHealth h) {
        return UserDtos.HealthView.builder()
                .id(String.valueOf(h.getId()))
                .diabetesType(h.getDiabetesType())
                .diabetesTypeText(h.getDiabetesTypeText())
                .diagnosedYm(h.getDiagnosedYm())
                .age(h.getAge())
                .medication(h.getMedication())
                .exerciseHabit(h.getExerciseHabit())
                .targetFasting(h.getTargetFasting())
                .targetPostprandial(h.getTargetPostprandial())
                .build();
    }
}
