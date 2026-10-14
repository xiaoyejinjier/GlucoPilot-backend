package com.threadfit.glucopilot.glucose.admin;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseNodeMapper;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminHealthRecordServiceTest {

    private static final long USER_ID = 7L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 9, 29);

    @Mock
    private GlucoseRecordMapper glucoseRecordMapper;
    @Mock
    private MealRecordMapper mealRecordMapper;
    @Mock
    private GlucoseNodeMapper glucoseNodeMapper;
    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private UserProfileMapper userProfileMapper;

    @InjectMocks
    private AdminHealthRecordService service;

    @Test
    void mergesGlucoseAndMealRecordsIntoNineSlotsWithoutTreatingPredictionAsMeasurement() {
        when(glucoseRecordMapper.selectList(any())).thenReturn(List.of(
                glucose("before_breakfast", "6.1", 1),
                glucose("after_breakfast", "9.2", 2)));
        when(mealRecordMapper.selectList(any())).thenReturn(List.of(meal("breakfast", "燕麦、鸡蛋", "8.8")));
        when(glucoseNodeMapper.selectList(any())).thenReturn(List.of());

        UserAccount account = new UserAccount();
        account.setId(USER_ID);
        account.setUsername("tester");
        when(userAccountMapper.selectList(any())).thenReturn(List.of(account));
        UserProfile profile = new UserProfile();
        profile.setUserId(USER_ID);
        profile.setNickname("测试用户");
        when(userProfileMapper.selectList(any())).thenReturn(List.of(profile));

        AdminHealthRecordDtos.Result result = service.query(
                RECORD_DATE, RECORD_DATE, null, new PageQuery());

        assertEquals(3L, result.getOverview().getTotalRecords());
        assertEquals(2L, result.getOverview().getGlucoseRecords());
        assertEquals(1L, result.getOverview().getMealRecords());
        assertEquals(new BigDecimal("7.7"), result.getOverview().getAvgGlucose());
        assertEquals(50, result.getOverview().getComplianceRate());
        assertEquals(1L, result.getOverview().getAbnormalRecords());

        assertEquals(1L, result.getPage().getTotal());
        AdminHealthRecordDtos.DayRow row = result.getPage().getRecords().get(0);
        assertEquals(9, row.getSlots().size());
        assertEquals(3, row.getCompletedCount());
        assertEquals("测试用户", row.getNickname());
        assertTrue(row.getSlots().get("breakfast").getRecorded());
        assertTrue(row.getSlots().get("breakfast").getMeal());
        assertNull(row.getSlots().get("breakfast").getGlucoseValue());
        assertEquals(new BigDecimal("8.8"), row.getSlots().get("breakfast").getPredictNow());
        assertFalse(row.getSlots().get("before_lunch").getRecorded());
    }

    @Test
    void rejectsRangesLongerThanNinetyTwoDays() {
        PageQuery query = new PageQuery();
        assertThrows(BizException.class, () -> service.query(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 3), null, query));
    }

    private static GlucoseRecord glucose(String nodeCode, String value, int sourceType) {
        GlucoseRecord record = new GlucoseRecord();
        record.setUserId(USER_ID);
        record.setRecordDate(RECORD_DATE);
        record.setNodeCode(nodeCode);
        record.setGlucoseValue(new BigDecimal(value));
        record.setSourceType(sourceType);
        return record;
    }

    private static MealRecord meal(String nodeCode, String foods, String predictNow) {
        MealRecord record = new MealRecord();
        record.setUserId(USER_ID);
        record.setRecordDate(RECORD_DATE);
        record.setNodeCode(nodeCode);
        record.setFoodsText(foods);
        record.setCarbsG(new BigDecimal("42"));
        record.setPredictNow(new BigDecimal(predictNow));
        record.setPredictSuggest(new BigDecimal("7.3"));
        return record;
    }
}
