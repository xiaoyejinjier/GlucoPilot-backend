package com.threadfit.glucopilot.prediction.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;

import org.junit.jupiter.api.Test;

class PredictionControllerTest {

    @Test
    void predictAlwaysReturnsFeatureRemoved() {
        BizException error =
                assertThrows(BizException.class, () -> new PredictionController().predict());
        assertEquals(ErrorCode.FEATURE_REMOVED, error.getErrorCode());
        assertEquals(410, error.getErrorCode().getHttpStatus().value());
        assertEquals(41000, error.getErrorCode().getCode());
    }
}
