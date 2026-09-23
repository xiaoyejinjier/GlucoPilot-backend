package com.threadfit.glucopilot.user.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.user.dto.AdminUserDtos;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserHealth;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理端读取业务库中的 C 端用户，禁止使用前端 Mock 用户。 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final BigDecimal TARGET_LOW = new BigDecimal("3.9");
    private static final BigDecimal TARGET_HIGH = new BigDecimal("7.8");

    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserHealthMapper userHealthMapper;
    private final GlucoseRecordMapper glucoseRecordMapper;

    public PageResult<AdminUserDtos.UserItem> page(
            String keyword, Integer accountStatus, PageQuery query) {
        if (accountStatus != null) {
            CodeEnum.of(CodeEnum.AccountStatus.class, accountStatus);
        }

        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        List<Long> nicknameUserIds = normalizedKeyword == null
                ? Collections.emptyList()
                : userProfileMapper.selectList(new QueryWrapper<UserProfile>()
                        .select("user_id")
                        .like("nickname", normalizedKeyword)).stream()
                        .map(UserProfile::getUserId)
                        .distinct()
                        .toList();

        LambdaQueryWrapper<UserAccount> wrapper = new LambdaQueryWrapper<UserAccount>()
                .eq(accountStatus != null, UserAccount::getAccountStatus, accountStatus)
                .orderByDesc(UserAccount::getGmtCreate)
                .orderByDesc(UserAccount::getId);
        if (normalizedKeyword != null) {
            wrapper.and(condition -> {
                condition.like(UserAccount::getUsername, normalizedKeyword)
                        .or()
                        .like(UserAccount::getPhone, normalizedKeyword);
                if (!nicknameUserIds.isEmpty()) {
                    condition.or().in(UserAccount::getId, nicknameUserIds);
                }
            });
        }

        Page<UserAccount> rows = userAccountMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<Long> userIds = rows.getRecords().stream().map(UserAccount::getId).toList();
        if (userIds.isEmpty()) {
            return PageResult.of(rows.getCurrent(), rows.getSize(), rows.getTotal(), Collections.emptyList());
        }

        Map<Long, UserProfile> profiles = userProfileMapper.selectList(new QueryWrapper<UserProfile>()
                        .in("user_id", userIds)).stream()
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity(), (a, b) -> a));
        Map<Long, UserHealth> healthRows = userHealthMapper.selectList(new QueryWrapper<UserHealth>()
                        .in("user_id", userIds)).stream()
                .collect(Collectors.toMap(UserHealth::getUserId, Function.identity(), (a, b) -> a));
        Map<Long, List<GlucoseRecord>> glucoseRows = glucoseRecordMapper.selectList(
                        new QueryWrapper<GlucoseRecord>().in("user_id", userIds)).stream()
                .collect(Collectors.groupingBy(GlucoseRecord::getUserId));

        List<AdminUserDtos.UserItem> items = rows.getRecords().stream()
                .map(account -> toItem(
                        account,
                        profiles.get(account.getId()),
                        healthRows.get(account.getId()),
                        glucoseRows.getOrDefault(account.getId(), Collections.emptyList())))
                .toList();
        return PageResult.of(rows.getCurrent(), rows.getSize(), rows.getTotal(), items);
    }

    @Transactional
    public AdminUserDtos.UpdatedResult updateStatus(Long userId, Integer accountStatus) {
        CodeEnum.of(CodeEnum.AccountStatus.class, accountStatus);
        if (accountStatus != CodeEnum.AccountStatus.DISABLED.getCode()
                && accountStatus != CodeEnum.AccountStatus.NORMAL.getCode()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户管理仅允许禁用或解禁账号");
        }
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        account.setAccountStatus(accountStatus);
        userAccountMapper.updateById(account);
        return AdminUserDtos.UpdatedResult.builder().updated(true).build();
    }

    private static AdminUserDtos.UserItem toItem(
            UserAccount account, UserProfile profile, UserHealth health, List<GlucoseRecord> records) {
        String nickname = profile != null && StringUtils.hasText(profile.getNickname())
                ? profile.getNickname()
                : StringUtils.hasText(account.getUsername())
                        ? account.getUsername()
                        : "用户#" + account.getId();
        String avatarText = profile != null && StringUtils.hasText(profile.getAvatarText())
                ? profile.getAvatarText()
                : nickname.substring(0, 1);
        long validCount = records.stream().filter(row -> row.getGlucoseValue() != null).count();
        long targetCount = records.stream()
                .map(GlucoseRecord::getGlucoseValue)
                .filter(value -> value != null
                        && value.compareTo(TARGET_LOW) >= 0
                        && value.compareTo(TARGET_HIGH) < 0)
                .count();
        int complianceRate = validCount == 0 ? 0 : (int) Math.round(targetCount * 100.0 / validCount);

        return AdminUserDtos.UserItem.builder()
                .id(String.valueOf(account.getId()))
                .username(account.getUsername())
                .nickname(nickname)
                .avatarText(avatarText)
                .phoneMasked(UserAccountService.maskPhone(account.getPhone()))
                .age(health == null ? null : health.getAge())
                .diabetesTypeText(health == null || !StringUtils.hasText(health.getDiabetesTypeText())
                        ? "未填写"
                        : health.getDiabetesTypeText())
                .accountStatus(account.getAccountStatus())
                .registeredAt(account.getGmtCreate())
                .lastActiveAt(account.getLastLoginAt())
                .recordCount((long) records.size())
                .mealCount(0L)
                .complianceRate(complianceRate)
                .build();
    }
}
