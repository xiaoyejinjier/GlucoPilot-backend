package com.threadfit.glucopilot.user.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.meal.vo.MealVos;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.dto.AdminUserDtos;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理端读取业务库中的 C 端用户，禁止使用前端 Mock 用户。 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final BigDecimal TARGET_LOW = new BigDecimal("3.9");
    private static final BigDecimal TARGET_HIGH = new BigDecimal("7.8");
    /** 档案趋势窗口（近 7 日，仅含有记录的天）。 */
    private static final int TREND_DAYS = 7;
    /** 档案饮食汇总窗口（近 30 日）。 */
    private static final int MEAL_SUMMARY_DAYS = 30;

    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserHealthMapper userHealthMapper;
    private final GlucoseRecordMapper glucoseRecordMapper;
    private final MealRecordService mealRecordService;
    private final GlucosePeriodService glucosePeriodService;

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
        // TODO 技术债：user 模块直连 profile.health.mapper.UserHealthMapper，待 profile.health
        //  提供批量查询接口后改为依赖 service，避免跨模块 Mapper 耦合（与 GlucoseRecordMapper 同类）。
        Map<Long, UserHealth> healthRows = userHealthMapper.selectList(new QueryWrapper<UserHealth>()
                        .in("user_id", userIds)).stream()
                .collect(Collectors.toMap(UserHealth::getUserId, Function.identity(), (a, b) -> a));
        Map<Long, List<GlucoseRecord>> glucoseRows = glucoseRecordMapper.selectList(
                        new QueryWrapper<GlucoseRecord>().in("user_id", userIds)).stream()
                .collect(Collectors.groupingBy(GlucoseRecord::getUserId));
        Map<Long, Long> mealCounts = mealRecordService.countActiveByUserIds(userIds);

        List<AdminUserDtos.UserItem> items = rows.getRecords().stream()
                .map(account -> toItem(
                        account,
                        profiles.get(account.getId()),
                        healthRows.get(account.getId()),
                        glucoseRows.getOrDefault(account.getId(), Collections.emptyList()),
                        mealCounts.getOrDefault(account.getId(), 0L)))
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
        // 注销中的 2/3/4 是注销流程自己的中间态与终态，管理员不能直接改成 NORMAL。
        // 改回 NORMAL 等于替用户撤销注销——管理员不该有这个权力（方案 §3.4 / §7 明确不代撤销）。
        int current = account.getAccountStatus() == null ? -1 : account.getAccountStatus();
        if (current == CodeEnum.AccountStatus.CANCELLING.getCode()
                || current == CodeEnum.AccountStatus.CANCELLED.getCode()
                || current == CodeEnum.AccountStatus.CANCELLED_RECOVERABLE.getCode()) {
            throw new BizException(ErrorCode.CONFLICT, "该账号处于注销流程中或已注销，不可在此修改状态");
        }
        account.setAccountStatus(accountStatus);
        userAccountMapper.updateById(account);
        return AdminUserDtos.UpdatedResult.builder().updated(true).build();
    }

    public AdminUserDtos.UserItem detail(Long userId) {
        UserAccount account=userAccountMapper.selectById(userId);
        if(account==null)throw new BizException(ErrorCode.NOT_FOUND,"用户不存在");
        UserProfile profile=userProfileMapper.selectOne(new QueryWrapper<UserProfile>().eq("user_id",userId).last("LIMIT 1"));
        UserHealth health=userHealthMapper.selectOne(new QueryWrapper<UserHealth>().eq("user_id",userId).last("LIMIT 1"));
        List<GlucoseRecord> records=glucoseRecordMapper.selectList(new QueryWrapper<GlucoseRecord>().eq("user_id",userId));
        return toItem(account,profile,health,records,mealRecordService.countActiveByUserIds(List.of(userId)).getOrDefault(userId,0L));
    }

    /**
     * 管理端健康档案详情：聚合 user_account / user_profile / user_health 真实数据，
     * 趋势与分时段取自未删除的 glucose_record，饮食汇总经 meal service 聚合。
     * height / weight 取自 user_health（profile.health 域）；summary 无 AI 数据源，暂返回 null。
     */
    public AdminUserDtos.Archive archive(Long userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        UserProfile profile = userProfileMapper.selectOne(new QueryWrapper<UserProfile>()
                .eq("user_id", userId).last("LIMIT 1"));
        UserHealth health = userHealthMapper.selectOne(new QueryWrapper<UserHealth>()
                .eq("user_id", userId).last("LIMIT 1"));

        LocalDate today = LocalDate.now();
        LocalDate trendFrom = today.minusDays(TREND_DAYS - 1L);
        List<GlucoseRecord> records = glucoseRecordMapper.selectList(new QueryWrapper<GlucoseRecord>()
                .eq("user_id", userId)
                .ge("record_date", trendFrom)
                .le("record_date", today));
        MealVos.ArchiveSummary meal = mealRecordService.archiveSummary(
                userId, today.minusDays(MEAL_SUMMARY_DAYS - 1L), today);

        return AdminUserDtos.Archive.builder()
                .userId(String.valueOf(account.getId()))
                .owner(AdminUserDtos.Owner.builder()
                        .nickname(nicknameOf(account, profile))
                        .phoneMasked(PhoneUtils.mask(account.getPhone()))
                        .age(health == null ? null : health.getAge())
                        .diabetesTypeText(diabetesTypeTextOf(health))
                        .registeredAt(account.getGmtCreate())
                        .build())
                .profile(AdminUserDtos.Profile.builder()
                        .height(health == null ? null : health.getHeightCm())
                        .weight(health == null ? null : health.getWeightKg())
                        .medication(health == null ? null : health.getMedication())
                        .exerciseHabit(health == null ? null : health.getExerciseHabit())
                        .diagnosedYm(health == null ? null : health.getDiagnosedYm())
                        .build())
                .trend(trendOf(records, trendFrom, today))
                .nodeStats(nodeStatsOf(records))
                .mealSummary(AdminUserDtos.MealSummary.builder()
                        .recordDays(meal.getRecordDays())
                        .avgCarbsG(meal.getAvgCarbsG())
                        .avgCal(meal.getAvgCal())
                        .topFoods(meal.getTopFoods())
                        .build())
                // TODO 待接入 AI 档案小结后回填
                .summary(null)
                .build();
    }

    /** 按天聚合趋势：仅输出有记录的天，避免 null 均值污染前端均值计算。 */
    private static List<AdminUserDtos.TrendPoint> trendOf(
            List<GlucoseRecord> records, LocalDate from, LocalDate to) {
        Map<LocalDate, List<GlucoseRecord>> byDate = records.stream()
                .filter(r -> r.getRecordDate() != null)
                .collect(Collectors.groupingBy(GlucoseRecord::getRecordDate));
        List<AdminUserDtos.TrendPoint> points = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<GlucoseRecord> rows = byDate.getOrDefault(date, List.of());
            BigDecimal avg = GlucoseTrendService.average(rows);
            if (avg == null) {
                continue;
            }
            points.add(AdminUserDtos.TrendPoint.builder()
                    .date(date)
                    .avg(avg)
                    .okRate(okRateOf(rows))
                    .build());
        }
        return points;
    }

    /** 分时段均值：节点名取自字典，仅返回有记录的节点（含样本数）。 */
    private List<AdminUserDtos.NodeStat> nodeStatsOf(List<GlucoseRecord> records) {
        Map<String, List<GlucoseRecord>> byNode = records.stream()
                .filter(r -> r.getNodeCode() != null)
                .collect(Collectors.groupingBy(GlucoseRecord::getNodeCode));
        List<AdminUserDtos.NodeStat> stats = new ArrayList<>();
        for (GlucoseNode node : glucosePeriodService.listEnabledNodes()) {
            List<GlucoseRecord> rows = byNode.get(node.getNodeCode());
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            BigDecimal avg = GlucoseTrendService.average(rows);
            if (avg == null) {
                continue;
            }
            stats.add(AdminUserDtos.NodeStat.builder()
                    .node(node.getNodeCode())
                    .nodeName(node.getNodeName())
                    .avg(avg)
                    .samples(rows.size())
                    .build());
        }
        return stats;
    }

    /** 达标率：区间 [3.9, 7.8) 占比，保留整数百分比；无有效值为 0。 */
    private static Integer okRateOf(List<GlucoseRecord> rows) {
        long validCount = rows.stream().map(GlucoseRecord::getGlucoseValue).filter(Objects::nonNull).count();
        long targetCount = rows.stream()
                .map(GlucoseRecord::getGlucoseValue)
                .filter(value -> value != null
                        && value.compareTo(TARGET_LOW) >= 0
                        && value.compareTo(TARGET_HIGH) < 0)
                .count();
        return validCount == 0 ? 0 : (int) Math.round(targetCount * 100.0 / validCount);
    }

    /** 昵称兜底：资料昵称 → 账号名 → 用户#id。 */
    private static String nicknameOf(UserAccount account, UserProfile profile) {
        if (profile != null && StringUtils.hasText(profile.getNickname())) {
            return profile.getNickname();
        }
        return StringUtils.hasText(account.getUsername())
                ? account.getUsername()
                : "用户#" + account.getId();
    }

    /** 糖尿病类型文案：健康档案无值统一回退「未填写」。 */
    private static String diabetesTypeTextOf(UserHealth health) {
        return health == null || !StringUtils.hasText(health.getDiabetesTypeText())
                ? "未填写"
                : health.getDiabetesTypeText();
    }

    private static AdminUserDtos.UserItem toItem(
            UserAccount account, UserProfile profile, UserHealth health, List<GlucoseRecord> records,
            long mealCount) {
        String nickname = nicknameOf(account, profile);
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
                .phone(account.getPhone())
                .phoneMasked(PhoneUtils.mask(account.getPhone()))
                .age(health == null ? null : health.getAge())
                .diabetesTypeText(diabetesTypeTextOf(health))
                .accountStatus(account.getAccountStatus())
                .registeredAt(account.getGmtCreate())
                .lastActiveAt(account.getLastLoginAt())
                .recordCount((long) records.size())
                .mealCount(mealCount)
                .complianceRate(complianceRate)
                .build();
    }
}
