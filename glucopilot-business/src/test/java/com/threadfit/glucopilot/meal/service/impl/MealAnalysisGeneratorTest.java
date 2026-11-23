package com.threadfit.glucopilot.meal.service.impl;

import com.threadfit.glucopilot.meal.service.MealAnalysisGenerator;
import com.threadfit.glucopilot.meal.vo.MealVos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 分析编排：成功返回 READY，失败只置 FAILED 且不抛出。 */
@ExtendWith(MockitoExtension.class)
class MealAnalysisGeneratorTest {

    @Mock
    private MealAnalysisTxWriter analysisTxWriter;
    @Mock
    private MealAnalysisFailureWriter analysisFailureWriter;

    @InjectMocks
    private MealAnalysisGeneratorImpl generator;

    @Test
    void returnsReadyAndDetectsVersionChange() {
        when(analysisTxWriter.write(1L, 2L)).thenReturn(analysis("v2"));

        MealAnalysisGenerator.Result result = generator.generate(1L, 2L, "v1");

        assertEquals(MealAnalysisGenerator.STATUS_READY, result.getStatus());
        assertTrue(result.isRuleVersionChanged());
        assertEquals("v2", result.getAnalysis().getRuleVersion());
        verify(analysisFailureWriter, never()).markFailed(any(), any());
    }

    @Test
    void sameRuleVersionIsNotReportedChanged() {
        when(analysisTxWriter.write(1L, 2L)).thenReturn(analysis("v2"));

        MealAnalysisGenerator.Result result = generator.generate(1L, 2L, "v2");

        assertEquals(MealAnalysisGenerator.STATUS_READY, result.getStatus());
        assertFalse(result.isRuleVersionChanged());
    }

    @Test
    void missingPreviousVersionIsNotReportedChanged() {
        when(analysisTxWriter.write(1L, 2L)).thenReturn(analysis("v2"));

        // 首次分析（无历史版本）不应提示前端「规则已变更」
        assertFalse(generator.generate(1L, 2L, null).isRuleVersionChanged());
        assertFalse(generator.generate(1L, 2L, "  ").isRuleVersionChanged());
    }

    @Test
    void failureMarksFailedAndReturnsFailedStatus() {
        when(analysisTxWriter.write(1L, 2L))
                .thenThrow(new IllegalStateException("暂无启用的适食规则"));

        MealAnalysisGenerator.Result result = generator.generate(1L, 2L, "v1");

        assertEquals(MealAnalysisGenerator.STATUS_FAILED, result.getStatus());
        assertNull(result.getAnalysis());
        assertFalse(result.isRuleVersionChanged());
        verify(analysisFailureWriter).markFailed(1L, 2L);
    }

    @Test
    void markFailedErrorDoesNotPropagate() {
        when(analysisTxWriter.write(1L, 2L)).thenThrow(new RuntimeException("db down"));
        doThrow(new RuntimeException("still down")).when(analysisFailureWriter).markFailed(1L, 2L);

        // 连 FAILED 标记都写不进去时，保存结果仍必须正常返回
        MealAnalysisGenerator.Result result = generator.generate(1L, 2L, null);

        assertEquals(MealAnalysisGenerator.STATUS_FAILED, result.getStatus());
        assertNull(result.getAnalysis());
    }

    private static MealVos.Analysis analysis(String ruleVersion) {
        return MealVos.Analysis.builder()
                .tier("LOW")
                .tierLabel("升糖负担较低")
                .summary("摘要")
                .ruleVersion(ruleVersion)
                .build();
    }
}
