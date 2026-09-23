package com.threadfit.glucopilot.device.service;

import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.device.port.GlucoseIngestPort;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备同步现在只是 glucose 模块的转交层：这里只验证参数透传，
 * 「餐点节点被拒」「越界血糖值被拒」等业务校验见 GlucoseRecordServiceTest。
 */
@ExtendWith(MockitoExtension.class)
class GlucoseIngestServiceTest {

    @Mock
    private GlucoseRecordService glucoseRecordService;

    @InjectMocks
    private GlucoseIngestService service;

    @Test
    void delegatesToGlucoseRecordService() {
        when(glucoseRecordService.upsertByNode(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(GlucoseDtos.UpsertResult.builder().id("99").created(true).build());

        GlucoseIngestPort.IngestResult result = service.upsertByNode(new GlucoseIngestPort.IngestCommand(
                1L, LocalDate.of(2026, 9, 16), "before_lunch", new BigDecimal("6.2"),
                CodeEnum.GlucoseSourceType.DEVICE.getCode(), "device:MOCK", LocalDateTime.now(), "ext-1"));

        assertTrue(result.created());
        assertEquals(99L, result.recordId());

        verify(glucoseRecordService).upsertByNode(
                eq(1L), eq(LocalDate.of(2026, 9, 16)), eq("before_lunch"), eq(new BigDecimal("6.2")),
                eq(CodeEnum.GlucoseSourceType.DEVICE.getCode()), eq("device:MOCK"),
                isNull(), any(), eq("ext-1"));
    }
}
