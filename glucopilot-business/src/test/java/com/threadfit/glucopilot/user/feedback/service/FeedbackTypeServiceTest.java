package com.threadfit.glucopilot.user.feedback.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.dto.FeedbackDtos;
import com.threadfit.glucopilot.user.mapper.FeedbackTypeMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

class FeedbackTypeServiceTest {
    @Test
    void dictionaryControlsSubmissionButDisabledNamesRemainInHistory() {
        var mapper = mock(FeedbackTypeMapper.class);
        var enabled = new FeedbackDtos.TypeItem();
        enabled.setId(5);
        enabled.setName("设备支持");
        enabled.setDeviceRelated(true);
        var disabled = new FeedbackDtos.TypeItem();
        disabled.setId(1);
        disabled.setName("产品建议");
        disabled.setDeviceRelated(false);
        when(mapper.selectTypes(true)).thenReturn(List.of(enabled));
        when(mapper.selectTypes(false)).thenReturn(List.of(disabled, enabled));
        var service = new FeedbackTypeService(mapper);
        assertTrue(service.requireEnabled(5).getDeviceRelated());
        assertThrows(BizException.class, () -> service.requireEnabled(1));
        assertThrows(BizException.class, () -> service.requireEnabled(null));
        assertEquals("产品建议", service.names().get(1));
    }
}
