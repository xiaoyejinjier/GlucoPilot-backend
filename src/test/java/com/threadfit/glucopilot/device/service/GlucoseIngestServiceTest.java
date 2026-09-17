package com.threadfit.glucopilot.device.service;

import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.entity.GlucoseRecord;
import com.threadfit.glucopilot.device.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.device.port.GlucoseIngestPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlucoseIngestServiceTest {

    @Mock
    private GlucoseRecordMapper glucoseRecordMapper;

    @InjectMocks
    private GlucoseIngestService service;

    @Test
    void rejectsMealNode() {
        var cmd = new GlucoseIngestPort.IngestCommand(
                1L, LocalDate.now(), "breakfast", new BigDecimal("5.0"),
                CodeEnum.GlucoseSourceType.DEVICE.getCode(), null, LocalDateTime.now(), null);
        assertThrows(BizException.class, () -> service.upsertByNode(cmd));
    }

    @Test
    void insertsWhenAbsent() {
        when(glucoseRecordMapper.selectOne(any())).thenReturn(null);
        when(glucoseRecordMapper.insert(any(GlucoseRecord.class))).thenAnswer(inv -> {
            GlucoseRecord row = inv.getArgument(0);
            row.setId(99L);
            return 1;
        });

        var result = service.upsertByNode(new GlucoseIngestPort.IngestCommand(
                1L, LocalDate.of(2026, 9, 16), "before_lunch", new BigDecimal("6.2"),
                CodeEnum.GlucoseSourceType.DEVICE.getCode(), "device:MOCK", LocalDateTime.now(), "ext-1"));

        assertTrue(result.created());
        assertEquals(99L, result.recordId());
        ArgumentCaptor<GlucoseRecord> captor = ArgumentCaptor.forClass(GlucoseRecord.class);
        verify(glucoseRecordMapper).insert(captor.capture());
        assertEquals("before_lunch", captor.getValue().getNodeCode());
        assertEquals(CodeEnum.GlucoseSourceType.DEVICE.getCode(), captor.getValue().getSourceType());
    }
}
