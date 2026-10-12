package com.threadfit.glucopilot.user.account;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.meal.vo.MealVos;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.dto.AdminUserDtos;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private UserProfileMapper userProfileMapper;
    @Mock
    private UserHealthMapper userHealthMapper;
    @Mock
    private GlucoseRecordMapper glucoseRecordMapper;
    @Mock
    private MealRecordService mealRecordService;
    @Mock
    private GlucosePeriodService glucosePeriodService;

    /** 冻结口径：趋势近 7 日 → 起止相差 6 天；饮食汇总近 30 日 → 相差 29 天。 */
    private static final long TREND_SPAN_DAYS = 6;
    private static final long MEAL_SPAN_DAYS = 29;

    @InjectMocks
    private AdminUserService service;

    @Test
    void pageUsesDatabaseRowsAndBuildsMetrics() {
        UserAccount account = new UserAccount();
        account.setId(3L);
        account.setUsername("test_user");
        account.setPhone("13812345678");
        account.setAccountStatus(1);
        account.setGmtCreate(LocalDateTime.of(2026, 9, 20, 10, 0));
        when(userAccountMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<UserAccount> page = invocation.getArgument(0);
            page.setRecords(List.of(account));
            page.setTotal(1);
            return page;
        });

        UserProfile profile = new UserProfile();
        profile.setUserId(3L);
        profile.setNickname("测试用户");
        when(userProfileMapper.selectList(any())).thenReturn(List.of(profile));

        UserHealth health = new UserHealth();
        health.setUserId(3L);
        health.setAge(36);
        health.setDiabetesTypeText("2 型糖尿病");
        when(userHealthMapper.selectList(any())).thenReturn(List.of(health));

        GlucoseRecord target = glucose(3L, "6.1");
        GlucoseRecord high = glucose(3L, "9.2");
        when(glucoseRecordMapper.selectList(any())).thenReturn(List.of(target, high));

        when(mealRecordService.countActiveByUserIds(any())).thenReturn(Map.of(3L, 5L));

        PageResult<AdminUserDtos.UserItem> result = service.page(null, null, new PageQuery());

        assertEquals(1, result.getTotal());
        AdminUserDtos.UserItem item = result.getRecords().getFirst();
        assertEquals("3", item.getId());
        assertEquals("test_user", item.getUsername());
        assertEquals("测试用户", item.getNickname());
        assertEquals("138****5678", item.getPhoneMasked());
        assertEquals(2L, item.getRecordCount());
        assertEquals(5L, item.getMealCount());
        assertEquals(50, item.getComplianceRate());
    }

    @Test
    void updateStatusChangesActualAccount() {
        UserAccount account = new UserAccount();
        account.setId(3L);
        account.setAccountStatus(1);
        when(userAccountMapper.selectById(3L)).thenReturn(account);

        AdminUserDtos.UpdatedResult result = service.updateStatus(3L, 0);

        assertTrue(result.getUpdated());
        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getAccountStatus());
    }

    @Test
    void missingAccountUsesCommonNotFoundCode() {
        when(userAccountMapper.selectById(99L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.updateStatus(99L, 0));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void archiveMissingUserUsesCommonNotFoundCode() {
        when(userAccountMapper.selectById(99L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.archive(99L));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void archiveUsesFrozenWindowsAndPassesThroughRealData() {
        UserProfile profile = new UserProfile();
        profile.setUserId(3L);
        profile.setNickname("测试用户");
        UserHealth health = new UserHealth();
        health.setUserId(3L);
        health.setAge(36);
        health.setDiabetesTypeText("2 型糖尿病");
        health.setMedication("二甲双胍 0.5g bid");
        health.setExerciseHabit("每周快走 3 次");
        health.setDiagnosedYm("2021-06");
        MealVos.ArchiveSummary meal = MealVos.ArchiveSummary.builder()
                .recordDays(12)
                .avgCarbsG(new BigDecimal("46"))
                .avgCal(new BigDecimal("528"))
                .topFoods(List.of("米饭", "鸡胸肉"))
                .build();
        stubArchiveDependencies(3L, profile, health, List.of(), List.of(), meal);

        AdminUserDtos.Archive archive = service.archive(3L);

        // 冻结口径断言「窗口长度」而非绝对日期，规避跨零点抖动
        ArgumentCaptor<QueryWrapper> glucoseCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(glucoseRecordMapper).selectList(glucoseCaptor.capture());
        List<LocalDate> glucoseWindow = localDatesOf(glucoseCaptor.getValue());
        assertEquals(2, glucoseWindow.size());
        assertEquals(TREND_SPAN_DAYS, ChronoUnit.DAYS.between(glucoseWindow.get(0), glucoseWindow.get(1)));

        ArgumentCaptor<LocalDate> mealFrom = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> mealTo = ArgumentCaptor.forClass(LocalDate.class);
        verify(mealRecordService).archiveSummary(eq(3L), mealFrom.capture(), mealTo.capture());
        assertEquals(MEAL_SPAN_DAYS, ChronoUnit.DAYS.between(mealFrom.getValue(), mealTo.getValue()));

        assertEquals("3", archive.getUserId());
        assertEquals("测试用户", archive.getOwner().getNickname());
        assertEquals("138****5678", archive.getOwner().getPhoneMasked());
        assertEquals(36, archive.getOwner().getAge());
        assertEquals("2 型糖尿病", archive.getOwner().getDiabetesTypeText());
        assertEquals(LocalDateTime.of(2026, 9, 20, 10, 0), archive.getOwner().getRegisteredAt());
        assertEquals("二甲双胍 0.5g bid", archive.getProfile().getMedication());
        assertEquals("每周快走 3 次", archive.getProfile().getExerciseHabit());
        assertEquals("2021-06", archive.getProfile().getDiagnosedYm());
        // 无数据源字段恒 null（TODO 未落库）
        assertNull(archive.getProfile().getHeight());
        assertNull(archive.getProfile().getWeight());
        assertNull(archive.getSummary());
        assertEquals(12, archive.getMealSummary().getRecordDays());
        assertEquals(0, new BigDecimal("46").compareTo(archive.getMealSummary().getAvgCarbsG()));
        assertEquals(0, new BigDecimal("528").compareTo(archive.getMealSummary().getAvgCal()));
        assertEquals(List.of("米饭", "鸡胸肉"), archive.getMealSummary().getTopFoods());
        assertTrue(archive.getTrend().isEmpty());
        assertTrue(archive.getNodeStats().isEmpty());
    }

    @Test
    void archiveBuildsTrendOnlyForRecordedDaysAndAppliesOkRateBoundary() {
        UserHealth health = new UserHealth();
        health.setUserId(3L);
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        List<GlucoseRecord> records = List.of(
                glucoseAt(3L, yesterday, "before_breakfast", "5.5"),
                glucoseAt(3L, today, "before_breakfast", "3.9"),
                glucoseAt(3L, today, "after_breakfast", "7.8"));
        stubArchiveDependencies(3L, null, health, records,
                List.of(node("before_breakfast", "早餐前"),
                        node("after_breakfast", "早餐后"),
                        node("before_lunch", "中餐前")),
                emptyMealSummary());

        AdminUserDtos.Archive archive = service.archive(3L);

        // 仅输出有记录的天
        assertEquals(2, archive.getTrend().size());
        assertEquals(yesterday, archive.getTrend().get(0).getDate());
        assertEquals(100, archive.getTrend().get(0).getOkRate());
        assertEquals(today, archive.getTrend().get(1).getDate());
        // 3.9 属达标区间（含下界），7.8 不达标（上界开）→ 1/2
        assertEquals(50, archive.getTrend().get(1).getOkRate());
        // 「中餐前」无记录 → 不出现在分时段
        assertEquals(2, archive.getNodeStats().size());
        assertEquals("before_breakfast", archive.getNodeStats().get(0).getNode());
        assertEquals("早餐前", archive.getNodeStats().get(0).getNodeName());
        assertEquals(2, archive.getNodeStats().get(0).getSamples());
        assertEquals("after_breakfast", archive.getNodeStats().get(1).getNode());
        assertEquals(1, archive.getNodeStats().get(1).getSamples());
    }

    @Test
    void archiveFallsBackWhenProfileAndHealthMissing() {
        stubArchiveDependencies(7L, null, null, List.of(), List.of(), emptyMealSummary());

        AdminUserDtos.Archive archive = service.archive(7L);

        assertEquals("user_7", archive.getOwner().getNickname());
        assertEquals("未填写", archive.getOwner().getDiabetesTypeText());
        assertNull(archive.getOwner().getAge());
        assertNull(archive.getProfile().getMedication());
        assertNull(archive.getProfile().getExerciseHabit());
        assertNull(archive.getProfile().getDiagnosedYm());
        // 空数据不抛异常，数值类回落 null
        assertNull(archive.getMealSummary().getAvgCarbsG());
        assertNull(archive.getMealSummary().getAvgCal());
        assertTrue(archive.getMealSummary().getTopFoods().isEmpty());
    }

    /** 统一桩：档案聚合所依赖的 6 个读取入口，按用例给足返回值（避免 STRICT_STUBS 多余桩）。 */
    private void stubArchiveDependencies(Long userId, UserProfile profile, UserHealth health,
            List<GlucoseRecord> records, List<GlucoseNode> nodes, MealVos.ArchiveSummary mealSummary) {
        UserAccount account = new UserAccount();
        account.setId(userId);
        account.setUsername("user_" + userId);
        account.setPhone("13812345678");
        account.setAccountStatus(1);
        account.setGmtCreate(LocalDateTime.of(2026, 9, 20, 10, 0));
        when(userAccountMapper.selectById(userId)).thenReturn(account);
        when(userProfileMapper.selectOne(any())).thenReturn(profile);
        when(userHealthMapper.selectOne(any())).thenReturn(health);
        when(glucoseRecordMapper.selectList(any())).thenReturn(records);
        when(glucosePeriodService.listEnabledNodes()).thenReturn(nodes);
        when(mealRecordService.archiveSummary(anyLong(), any(), any())).thenReturn(mealSummary);
    }

    /** 从 QueryWrapper 参数表中取出 LocalDate 实参，用于断言冻结窗口。 */
    private static List<LocalDate> localDatesOf(QueryWrapper<?> wrapper) {
        // MP 3.5.x 的条件参数是懒登记：先触发一次 SQL 段生成，paramNameValuePairs 才会有值
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values().stream()
                .filter(LocalDate.class::isInstance)
                .map(LocalDate.class::cast)
                .sorted()
                .toList();
    }

    private static GlucoseRecord glucoseAt(Long userId, LocalDate date, String nodeCode, String value) {
        GlucoseRecord row = new GlucoseRecord();
        row.setUserId(userId);
        row.setRecordDate(date);
        row.setNodeCode(nodeCode);
        row.setGlucoseValue(new BigDecimal(value));
        return row;
    }

    private static GlucoseNode node(String nodeCode, String nodeName) {
        GlucoseNode node = new GlucoseNode();
        node.setNodeCode(nodeCode);
        node.setNodeName(nodeName);
        node.setIsEnabled(1);
        return node;
    }

    private static MealVos.ArchiveSummary emptyMealSummary() {
        return MealVos.ArchiveSummary.builder().recordDays(0).topFoods(List.of()).build();
    }

    private static GlucoseRecord glucose(Long userId, String value) {
        GlucoseRecord row = new GlucoseRecord();
        row.setUserId(userId);
        row.setGlucoseValue(new BigDecimal(value));
        return row;
    }
}
