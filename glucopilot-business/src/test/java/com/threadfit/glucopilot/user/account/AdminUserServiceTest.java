package com.threadfit.glucopilot.user.account;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.meal.service.MealRecordService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private static GlucoseRecord glucose(Long userId, String value) {
        GlucoseRecord row = new GlucoseRecord();
        row.setUserId(userId);
        row.setGlucoseValue(new BigDecimal(value));
        return row;
    }
}
