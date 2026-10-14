package com.threadfit.glucopilot.glucose.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseNodeMapper;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理端按用户和日期聚合 glucose_record + meal_record，形成完整九时段看板。 */
@Service
@RequiredArgsConstructor
public class AdminHealthRecordService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 92;
    private static final BigDecimal TARGET_LOW = new BigDecimal("3.9");
    private static final BigDecimal TARGET_HIGH = new BigDecimal("7.8");

    private final GlucoseRecordMapper glucoseRecordMapper;
    private final MealRecordMapper mealRecordMapper;
    private final GlucoseNodeMapper glucoseNodeMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;

    public AdminHealthRecordDtos.Result query(
            LocalDate startDate, LocalDate endDate, String keyword, PageQuery pageQuery) {
        LocalDate resolvedEnd = endDate == null ? LocalDate.now() : endDate;
        LocalDate resolvedStart = startDate == null ? resolvedEnd.minusDays(DEFAULT_DAYS - 1L) : startDate;
        validateRange(resolvedStart, resolvedEnd);

        List<Long> restrictedUserIds = resolveRestrictedUserIds(keyword);
        if (restrictedUserIds != null && restrictedUserIds.isEmpty()) {
            return emptyResult(resolvedStart, resolvedEnd, pageQuery);
        }

        LambdaQueryWrapper<GlucoseRecord> glucoseQuery = new LambdaQueryWrapper<GlucoseRecord>()
                .ge(GlucoseRecord::getRecordDate, resolvedStart)
                .le(GlucoseRecord::getRecordDate, resolvedEnd)
                .orderByAsc(GlucoseRecord::getRecordDate)
                .orderByAsc(GlucoseRecord::getUserId)
                .orderByAsc(GlucoseRecord::getNodeCode);
        LambdaQueryWrapper<MealRecord> mealQuery = new LambdaQueryWrapper<MealRecord>()
                .ge(MealRecord::getRecordDate, resolvedStart)
                .le(MealRecord::getRecordDate, resolvedEnd)
                .orderByAsc(MealRecord::getRecordDate)
                .orderByAsc(MealRecord::getUserId)
                .orderByAsc(MealRecord::getNodeCode);
        if (restrictedUserIds != null) {
            glucoseQuery.in(GlucoseRecord::getUserId, restrictedUserIds);
            mealQuery.in(MealRecord::getUserId, restrictedUserIds);
        }
        List<GlucoseRecord> glucoseRecords = glucoseRecordMapper.selectList(glucoseQuery);
        List<MealRecord> mealRecords = mealRecordMapper.selectList(mealQuery);

        List<NodeDef> nodeDefs = loadNodeDefs();
        AdminHealthRecordDtos.Overview overview = buildOverview(
                resolvedStart, resolvedEnd, glucoseRecords, mealRecords, nodeDefs);
        PageResult<AdminHealthRecordDtos.DayRow> page = buildPage(
                pageQuery, glucoseRecords, mealRecords, nodeDefs);

        return AdminHealthRecordDtos.Result.builder()
                .startDate(resolvedStart)
                .endDate(resolvedEnd)
                .overview(overview)
                .page(page)
                .build();
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "startDate 不能晚于 endDate");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_DAYS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "九时段记录单次最多查询 92 天");
        }
    }

    /** null 表示不过滤用户，空集合表示关键词无匹配用户。 */
    private List<Long> resolveRestrictedUserIds(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String normalized = keyword.trim();
        List<Long> nicknameUserIds = userProfileMapper.selectList(new QueryWrapper<UserProfile>()
                        .select("user_id")
                        .like("nickname", normalized)).stream()
                .map(UserProfile::getUserId)
                .distinct()
                .toList();
        QueryWrapper<UserAccount> wrapper = new QueryWrapper<UserAccount>()
                .select("id")
                .and(condition -> {
                    condition.like("username", normalized)
                            .or()
                            .like("phone", normalized);
                    if (!nicknameUserIds.isEmpty()) {
                        condition.or().in("id", nicknameUserIds);
                    }
                });
        return userAccountMapper.selectList(wrapper).stream()
                .map(UserAccount::getId)
                .distinct()
                .toList();
    }

    private AdminHealthRecordDtos.Result emptyResult(
            LocalDate startDate, LocalDate endDate, PageQuery pageQuery) {
        AdminHealthRecordDtos.Overview overview = buildOverview(
                startDate, endDate, List.of(), List.of(), loadNodeDefs());
        return AdminHealthRecordDtos.Result.builder()
                .startDate(startDate)
                .endDate(endDate)
                .overview(overview)
                .page(PageResult.empty(pageQuery.getPageNum(), pageQuery.getPageSize()))
                .build();
    }

    private AdminHealthRecordDtos.Overview buildOverview(
            LocalDate startDate,
            LocalDate endDate,
            List<GlucoseRecord> glucoseRecords,
            List<MealRecord> mealRecords,
            List<NodeDef> nodeDefs) {
        Set<Long> activeUsers = new LinkedHashSet<>();
        Set<DayKey> dayKeys = new LinkedHashSet<>();
        glucoseRecords.forEach(record -> {
            activeUsers.add(record.getUserId());
            dayKeys.add(new DayKey(record.getUserId(), record.getRecordDate()));
        });
        mealRecords.forEach(record -> {
            activeUsers.add(record.getUserId());
            dayKeys.add(new DayKey(record.getUserId(), record.getRecordDate()));
        });

        List<BigDecimal> values = glucoseRecords.stream()
                .map(GlucoseRecord::getGlucoseValue)
                .filter(value -> value != null)
                .toList();
        long targetCount = values.stream().filter(AdminHealthRecordService::isTarget).count();
        long abnormalCount = values.size() - targetCount;

        Map<LocalDate, List<GlucoseRecord>> glucoseByDate = glucoseRecords.stream()
                .collect(Collectors.groupingBy(GlucoseRecord::getRecordDate));
        Map<LocalDate, Long> mealsByDate = mealRecords.stream()
                .collect(Collectors.groupingBy(MealRecord::getRecordDate, Collectors.counting()));
        List<AdminHealthRecordDtos.TrendPoint> trend = startDate.datesUntil(endDate.plusDays(1))
                .map(date -> {
                    List<GlucoseRecord> daily = glucoseByDate.getOrDefault(date, List.of());
                    return AdminHealthRecordDtos.TrendPoint.builder()
                            .date(date)
                            .glucoseCount((long) daily.size())
                            .mealCount(mealsByDate.getOrDefault(date, 0L))
                            .avgGlucose(average(daily.stream()
                                    .map(GlucoseRecord::getGlucoseValue)
                                    .filter(value -> value != null)
                                    .toList()))
                            .build();
                })
                .toList();

        Map<String, List<BigDecimal>> valuesByNode = glucoseRecords.stream()
                .filter(record -> record.getGlucoseValue() != null)
                .collect(Collectors.groupingBy(
                        GlucoseRecord::getNodeCode,
                        Collectors.mapping(GlucoseRecord::getGlucoseValue, Collectors.toList())));
        Map<String, Long> glucoseCountByNode = glucoseRecords.stream()
                .collect(Collectors.groupingBy(GlucoseRecord::getNodeCode, Collectors.counting()));
        Map<String, Long> mealCountByNode = mealRecords.stream()
                .collect(Collectors.groupingBy(MealRecord::getNodeCode, Collectors.counting()));
        List<AdminHealthRecordDtos.NodeStat> nodeStats = nodeDefs.stream()
                .map(node -> AdminHealthRecordDtos.NodeStat.builder()
                        .nodeCode(node.code())
                        .nodeName(node.name())
                        .meal(node.meal())
                        .recordCount(node.meal()
                                ? mealCountByNode.getOrDefault(node.code(), 0L)
                                : glucoseCountByNode.getOrDefault(node.code(), 0L))
                        .avgGlucose(node.meal() ? null : average(valuesByNode.getOrDefault(node.code(), List.of())))
                        .build())
                .toList();

        return AdminHealthRecordDtos.Overview.builder()
                .activeUsers((long) activeUsers.size())
                .recordDays((long) dayKeys.size())
                .glucoseRecords((long) glucoseRecords.size())
                .mealRecords((long) mealRecords.size())
                .totalRecords((long) glucoseRecords.size() + mealRecords.size())
                .avgGlucose(average(values))
                .complianceRate(values.isEmpty() ? 0 : (int) Math.round(targetCount * 100.0 / values.size()))
                .abnormalRecords(abnormalCount)
                .trend(trend)
                .nodeStats(nodeStats)
                .build();
    }

    private PageResult<AdminHealthRecordDtos.DayRow> buildPage(
            PageQuery pageQuery,
            List<GlucoseRecord> glucoseRecords,
            List<MealRecord> mealRecords,
            List<NodeDef> nodeDefs) {
        Set<DayKey> keySet = new LinkedHashSet<>();
        glucoseRecords.forEach(record -> keySet.add(new DayKey(record.getUserId(), record.getRecordDate())));
        mealRecords.forEach(record -> keySet.add(new DayKey(record.getUserId(), record.getRecordDate())));
        List<DayKey> keys = keySet.stream()
                .sorted(Comparator.comparing(DayKey::date).reversed()
                        .thenComparing(Comparator.comparing(DayKey::userId).reversed()))
                .toList();
        long total = keys.size();
        long offset = pageQuery.offset();
        if (offset >= total) {
            return PageResult.of(pageQuery.getPageNum(), pageQuery.getPageSize(), total, List.of());
        }
        int from = Math.toIntExact(offset);
        int to = Math.min(keys.size(), from + pageQuery.getPageSize());
        List<DayKey> pageKeys = keys.subList(from, to);

        Set<Long> pageUserIds = pageKeys.stream().map(DayKey::userId).collect(Collectors.toSet());
        Map<Long, UserAccount> accounts = userAccountMapper.selectList(
                        new QueryWrapper<UserAccount>().in("id", pageUserIds)).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
        Map<Long, UserProfile> profiles = userProfileMapper.selectList(
                        new QueryWrapper<UserProfile>().in("user_id", pageUserIds)).stream()
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity(), (a, b) -> a));

        Map<DayKey, List<GlucoseRecord>> glucoseByDay = glucoseRecords.stream()
                .collect(Collectors.groupingBy(record -> new DayKey(record.getUserId(), record.getRecordDate())));
        Map<DayKey, List<MealRecord>> mealByDay = mealRecords.stream()
                .collect(Collectors.groupingBy(record -> new DayKey(record.getUserId(), record.getRecordDate())));

        List<AdminHealthRecordDtos.DayRow> rows = pageKeys.stream()
                .map(key -> toDayRow(
                        key,
                        accounts.get(key.userId()),
                        profiles.get(key.userId()),
                        glucoseByDay.getOrDefault(key, List.of()),
                        mealByDay.getOrDefault(key, List.of()),
                        nodeDefs))
                .toList();
        return PageResult.of(pageQuery.getPageNum(), pageQuery.getPageSize(), total, rows);
    }

    private AdminHealthRecordDtos.DayRow toDayRow(
            DayKey key,
            UserAccount account,
            UserProfile profile,
            List<GlucoseRecord> glucoseRecords,
            List<MealRecord> mealRecords,
            List<NodeDef> nodeDefs) {
        String username = account == null || !StringUtils.hasText(account.getUsername())
                ? "user_" + key.userId()
                : account.getUsername();
        String nickname = profile == null || !StringUtils.hasText(profile.getNickname())
                ? username
                : profile.getNickname();

        Map<String, AdminHealthRecordDtos.Slot> slots = new LinkedHashMap<>();
        nodeDefs.forEach(node -> slots.put(node.code(), emptySlot(node)));
        glucoseRecords.forEach(record -> {
            NodeDef node = findNode(nodeDefs, record.getNodeCode(), false);
            slots.put(record.getNodeCode(), AdminHealthRecordDtos.Slot.builder()
                    .nodeCode(record.getNodeCode())
                    .nodeName(node.name())
                    .meal(false)
                    .recorded(true)
                    .glucoseValue(record.getGlucoseValue())
                    .sourceType(record.getSourceType())
                    .remark(record.getRemark())
                    .recordedAt(record.getRecordedAt())
                    .build());
        });
        mealRecords.forEach(record -> {
            NodeDef node = findNode(nodeDefs, record.getNodeCode(), true);
            slots.put(record.getNodeCode(), AdminHealthRecordDtos.Slot.builder()
                    .nodeCode(record.getNodeCode())
                    .nodeName(node.name())
                    .meal(true)
                    .recorded(true)
                    .foodsText(record.getFoodsText())
                    .carbsG(record.getCarbsG())
                    .predictNow(record.getPredictNow())
                    .predictSuggest(record.getPredictSuggest())
                    .recordedAt(record.getRecordedAt())
                    .build());
        });

        return AdminHealthRecordDtos.DayRow.builder()
                .userId(String.valueOf(key.userId()))
                .username(username)
                .nickname(nickname)
                .recordDate(key.date())
                .completedCount(glucoseRecords.size() + mealRecords.size())
                .glucoseCount(glucoseRecords.size())
                .mealCount(mealRecords.size())
                .avgGlucose(average(glucoseRecords.stream()
                        .map(GlucoseRecord::getGlucoseValue)
                        .filter(value -> value != null)
                        .toList()))
                .slots(slots)
                .build();
    }

    private List<NodeDef> loadNodeDefs() {
        List<GlucoseNode> nodes = glucoseNodeMapper.selectList(new LambdaQueryWrapper<GlucoseNode>()
                .eq(GlucoseNode::getIsEnabled, 1)
                .orderByAsc(GlucoseNode::getSortNo)
                .orderByAsc(GlucoseNode::getId));
        if (nodes != null && !nodes.isEmpty()) {
            return nodes.stream()
                    .map(node -> new NodeDef(node.getNodeCode(), node.getNodeName(), node.getIsMeal() != null && node.getIsMeal() == 1))
                    .toList();
        }
        return List.of(
                new NodeDef(GlucoseNodes.BEFORE_BREAKFAST, "早餐前", false),
                new NodeDef(GlucoseNodes.BREAKFAST, "早餐", true),
                new NodeDef(GlucoseNodes.AFTER_BREAKFAST, "早餐后", false),
                new NodeDef(GlucoseNodes.BEFORE_LUNCH, "中餐前", false),
                new NodeDef(GlucoseNodes.LUNCH, "中餐", true),
                new NodeDef(GlucoseNodes.AFTER_LUNCH, "中餐后", false),
                new NodeDef(GlucoseNodes.BEFORE_DINNER, "晚餐前", false),
                new NodeDef(GlucoseNodes.DINNER, "晚餐", true),
                new NodeDef(GlucoseNodes.AFTER_DINNER, "晚餐后", false));
    }

    private static NodeDef findNode(List<NodeDef> nodes, String code, boolean meal) {
        return nodes.stream()
                .filter(node -> node.code().equals(code))
                .findFirst()
                .orElse(new NodeDef(code, code, meal));
    }

    private static AdminHealthRecordDtos.Slot emptySlot(NodeDef node) {
        return AdminHealthRecordDtos.Slot.builder()
                .nodeCode(node.code())
                .nodeName(node.name())
                .meal(node.meal())
                .recorded(false)
                .build();
    }

    private static boolean isTarget(BigDecimal value) {
        return value != null && value.compareTo(TARGET_LOW) >= 0 && value.compareTo(TARGET_HIGH) < 0;
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }

    private record DayKey(Long userId, LocalDate date) {
    }

    private record NodeDef(String code, String name, boolean meal) {
    }
}
