package com.threadfit.glucopilot.user.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.Asserts;
import com.threadfit.glucopilot.common.util.EmailUtils;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.file.service.MediaFileService;
import com.threadfit.glucopilot.file.service.AvatarMediaService;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import com.threadfit.glucopilot.user.dto.UserDtos;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import com.threadfit.glucopilot.user.settings.UserSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端账号：聚合查询、资料部分更新、手机号换绑。
 * 健康档案读写归 profile.health（{@link HealthProfileService}），偏好设置归 user.settings，
 * 登录签发会话仍在 auth 模块，本服务只负责登录态下的资料读写。
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserSettingService userSettingService;
    private final AuthService authService;
    private final HealthProfileService healthProfileService;
    private final MediaFileService mediaFileService;

    public UserDtos.MeResponse me(Long userId) {
        UserAccount account = requireAccount(userId);
        return UserDtos.MeResponse.builder()
                .communityKey("u" + userId)
                .phone(account.getPhone())
                .accountStatus(account.getAccountStatus())
                .phoneMasked(PhoneUtils.mask(account.getPhone()))
                .emailMasked(EmailUtils.mask(account.getEmail()))
                .profile(toProfileView(requireProfile(userId)))
                .health(healthProfileService.healthViewOf(userId))
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
                && request.getAvatarText() == null && request.getGender() == null && request.getBio() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少提交一个待修改字段");
        }
        // 头像必须来自当前用户上传的可用文件，防止引用他人或伪造 URL；空串/空白表示清空，跳过归属校验
        String avatarUrl = nullIfBlank(request.getAvatarUrl());
        if (avatarUrl != null) {
            var avatar = mediaFileService.findAvailableAvatar(userId, avatarUrl);
            if (avatar == null) throw new BizException(ErrorCode.BIZ_REJECTED, "头像不属于当前用户");
            avatarUrl = AvatarMediaService.path(avatar.getId());
        }
        UserProfile profile = requireProfile(userId);
        // 部分更新：非 null 字段走 entity（触发审计填充），空串清空走 wrapper.set(=NULL)；
        // updateById 的 NOT_NULL 策略会跳过 null 字段，清空不生效
        UserProfile entity = new UserProfile();
        entity.setId(profile.getId());
        LambdaUpdateWrapper<UserProfile> wrapper = new LambdaUpdateWrapper<UserProfile>()
                .eq(UserProfile::getId, profile.getId());

        if (request.getNickname() != null) {
            Asserts.notBlank(request.getNickname(), ErrorCode.BAD_REQUEST, "昵称不能为空");
            String username = request.getNickname().trim();
            entity.setNickname(username);
            // user_account.username 是账号侧用户名，与资料昵称保持同步。
            UserAccount accountPatch = new UserAccount();
            accountPatch.setId(userId);
            accountPatch.setUsername(username);
            userAccountMapper.updateById(accountPatch);
        }
        if (request.getAvatarUrl() != null) {
            if (avatarUrl != null) {
                entity.setAvatarUrl(avatarUrl);
            } else {
                wrapper.set(UserProfile::getAvatarUrl, null);
            }
        }
        String avatarText = nullIfBlank(request.getAvatarText());
        if (request.getAvatarText() != null) {
            if (avatarText != null) {
                entity.setAvatarText(avatarText);
            } else {
                wrapper.set(UserProfile::getAvatarText, null);
            }
        }
        if (request.getGender() != null) {
            entity.setGender(request.getGender());
        }
        if (request.getBio() != null) {
            String bio=request.getBio().trim();
            if(bio.length()>160)throw new BizException(ErrorCode.BAD_REQUEST,"个性签名最多160字");
            entity.setBio(bio);
        }
        userProfileMapper.update(entity, wrapper);
        return UserDtos.UpdatedResult.builder().updated(true).build();
    }

    /** 换绑手机号：先验验证码（避免账号枚举），再查占用。 */
    @Transactional
    public UserDtos.PhoneBindResult bindPhone(Long userId, UserDtos.PhoneBindRequest request) {
        authService.consumeSmsCode(request.getPhone(), request.getVerifyCode());

        UserAccount account = requireAccount(userId);
        if (request.getPhone().equals(account.getPhone())) {
            return UserDtos.PhoneBindResult.builder().phoneMasked(PhoneUtils.mask(account.getPhone())).build();
        }
        UserAccount occupied = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getPhone, request.getPhone())
                .last("LIMIT 1"));
        if (occupied != null) {
            throw new BizException(ErrorCode.CONFLICT, "该手机号已被其他账号绑定");
        }
        account.setPhone(request.getPhone());
        userAccountMapper.updateById(account);
        return UserDtos.PhoneBindResult.builder().phoneMasked(PhoneUtils.mask(account.getPhone())).build();
    }

    public UserDtos.EmailBindResult bindEmail(Long userId, UserDtos.EmailBindRequest request) {
        String email = EmailUtils.normalize(request.getEmail());
        authService.consumeEmailCode(email, request.getVerifyCode());
        UserAccount account = requireAccount(userId);
        if (email.equals(account.getEmail())) {
            return UserDtos.EmailBindResult.builder().emailMasked(EmailUtils.mask(account.getEmail())).build();
        }
        UserAccount occupied = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, email)
                .ne(UserAccount::getId, userId)
                .last("LIMIT 1"));
        if (occupied != null) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已被占用");
        }
        account.setEmail(email);
        userAccountMapper.updateById(account);
        return UserDtos.EmailBindResult.builder().emailMasked(EmailUtils.mask(account.getEmail())).build();
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

    /**
     * 账号注销：写墓碑 + 资料脱敏（同一事务）。
     * <p>
     * 两件事必须一起成功：只脱敏不写墓碑，账号还能登录；只写墓碑不脱敏，
     * 社区仍能显示真实昵称/头像。这是清理链路的最后一步，调用前所有业务数据已删完。
     */
    @Transactional
    public void anonymizeAccount(Long userId) {
        userProfileMapper.anonymizeByUserId(userId);
        userAccountMapper.tombstoneById(userId);
    }

    private static String nullIfBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static UserDtos.ProfileView toProfileView(UserProfile p) {
        return UserDtos.ProfileView.builder()
                .nickname(p.getNickname())
                .bio(p.getBio())
                .avatarUrl(p.getAvatarUrl())
                .avatarText(p.getAvatarText())
                .gender(p.getGender())
                .build();
    }
}
