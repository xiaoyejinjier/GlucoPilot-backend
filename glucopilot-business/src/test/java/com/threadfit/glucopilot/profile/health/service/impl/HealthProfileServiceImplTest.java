package com.threadfit.glucopilot.profile.health.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthProfileServiceImplTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserHealthMapper userHealthMapper;

    @InjectMocks
    private HealthProfileServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 MyBatis 容器，LambdaUpdateWrapper 解析实体列需要手动注册 TableInfo
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserHealth.class);
    }

    @Test
    void saveHealthDerivesTypeTextAndKeepsProvidedFields() {
        when(userHealthMapper.selectOne(any())).thenReturn(health());

        HealthSaveRequest request = new HealthSaveRequest();
        request.setDiabetesType(2);
        request.setAge(58);
        request.setDiagnosedYm("2020-03");
        request.setHeight(168);
        request.setWeight(new BigDecimal("66.5"));

        HealthView view = service.saveHealth(USER_ID, request);

        // 全量保存：非 null 字段走 entity
        ArgumentCaptor<UserHealth> entityCaptor = ArgumentCaptor.forClass(UserHealth.class);
        verify(userHealthMapper).update(entityCaptor.capture(), any());
        UserHealth entity = entityCaptor.getValue();
        assertEquals(1L, entity.getId());
        assertEquals(2, entity.getDiabetesType());
        assertEquals("2 型糖尿病", entity.getDiabetesTypeText());
        assertEquals(58, entity.getAge());
        assertEquals("2020-03", entity.getDiagnosedYm());
        assertEquals(168, entity.getHeightCm());
        assertEquals(0, new BigDecimal("66.5").compareTo(entity.getWeightKg()));
        // 未填字段显式清空（entity 保持 null，由 wrapper SET NULL）
        assertNull(entity.getMedication());
        assertEquals("2 型糖尿病", view.getDiabetesTypeText());
        assertEquals(168, view.getHeight());
        assertEquals(0, new BigDecimal("66.5").compareTo(view.getWeight()));
    }

    @Test
    void saveHealthClearsBodyMetricsWhenAbsent() {
        when(userHealthMapper.selectOne(any())).thenReturn(health());

        // 仅提交糖尿病类型 → 身高/体重按"清空"处理
        HealthSaveRequest request = new HealthSaveRequest();
        request.setDiabetesType(2);

        HealthView view = service.saveHealth(USER_ID, request);

        ArgumentCaptor<UserHealth> entityCaptor = ArgumentCaptor.forClass(UserHealth.class);
        verify(userHealthMapper).update(entityCaptor.capture(), any());
        // 置空字段不写入 entity（改由 wrapper SET NULL），视图回落 null
        assertNull(entityCaptor.getValue().getHeightCm());
        assertNull(entityCaptor.getValue().getWeightKg());
        assertNull(view.getHeight());
        assertNull(view.getWeight());
    }

    @Test
    void saveHealthDefaultsTypeToUnspecifiedAndRejectsFutureYm() {
        when(userHealthMapper.selectOne(any())).thenReturn(health());

        HealthSaveRequest request = new HealthSaveRequest();
        request.setDiagnosedYm(YearMonth.now().plusMonths(1).toString());

        assertThrows(BizException.class, () -> service.saveHealth(USER_ID, request));
        verify(userHealthMapper, never()).update(any(), any());
    }

    @Test
    void saveHealthUnspecifiedTypeDerivesUnfilledText() {
        when(userHealthMapper.selectOne(any())).thenReturn(health());

        HealthSaveRequest request = new HealthSaveRequest();

        HealthView view = service.saveHealth(USER_ID, request);

        assertEquals(0, view.getDiabetesType());
        assertEquals("未填写", view.getDiabetesTypeText());
    }

    @Test
    void healthViewOfReadsExistingRow() {
        when(userHealthMapper.selectOne(any())).thenReturn(health());

        HealthView view = service.healthViewOf(USER_ID);

        assertEquals(2, view.getDiabetesType());
        assertEquals("2 型糖尿病", view.getDiabetesTypeText());
        verify(userHealthMapper, never()).insert(any(UserHealth.class));
    }

    @Test
    void healthViewOfCreatesRowWhenMissing() {
        when(userHealthMapper.selectOne(any())).thenReturn(null);

        service.healthViewOf(USER_ID);

        // 缺失兜底新建，避免历史数据 500
        ArgumentCaptor<UserHealth> insertCaptor = ArgumentCaptor.forClass(UserHealth.class);
        verify(userHealthMapper).insert(insertCaptor.capture());
        assertEquals(USER_ID, insertCaptor.getValue().getUserId());
        assertEquals(0, insertCaptor.getValue().getDiabetesType());
    }

    private static UserHealth health() {
        UserHealth health = new UserHealth();
        health.setId(1L);
        health.setUserId(USER_ID);
        health.setDiabetesType(2);
        health.setDiabetesTypeText("2 型糖尿病");
        health.setMedication("二甲双胍");
        return health;
    }
}
