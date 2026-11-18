package com.threadfit.glucopilot.ai.model.service;

import com.threadfit.glucopilot.ai.model.dto.AiUsageDtos;
import com.threadfit.glucopilot.ai.model.mapper.AiCallLogMapper;
import com.threadfit.glucopilot.ai.model.mapper.AiCallUsageRow;
import com.threadfit.glucopilot.ai.model.service.impl.AiUsageServiceImpl;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用量报表：区间规则 + 汇总口径（数据源已切到 {@code ai_call_log} 账本）。
 *
 * <p>守三类易错点：①<b>区间必须是半开</b>（{@code [start 00:00, end+1d 00:00)}），
 * 写成闭区间会漏掉结束日当天带毫秒的行；②<b>历史行没有 model_code / scene_code</b> 时必须
 * 显式占位，否则前端会出现一片空白行、看起来像数据坏了；③<b>命中率为「没有上报」时是 null，
 * 不是 0</b>——两者在"要不要优化缓存"这件事上结论相反。
 */
class AiUsageServiceTest {

    private AiCallLogMapper callLogMapper;
    private AiUsageService service;

    @BeforeEach
    void setUp() {
        callLogMapper = mock(AiCallLogMapper.class);
        service = new AiUsageServiceImpl(callLogMapper);
    }

    @Test
    @DisplayName("不传日期时默认看最近七天，并把结束日的次日零点作为开区间上界")
    void defaultWindowIsLastSevenDays() {
        LocalDate today = LocalDate.now();
        when(callLogMapper.dailyUsage(any(), any())).thenReturn(List.of());

        AiUsageDtos.Report report = service.report(null, null);

        assertThat(report.startDate()).isEqualTo(today.minusDays(6).toString());
        assertThat(report.endDate()).isEqualTo(today.toString());
        verify(callLogMapper).dailyUsage(
                today.minusDays(6).atStartOfDay(),
                today.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("显式区间原样透传，上界是结束日次日零点")
    void explicitRangeUsesHalfOpenWindow() {
        when(callLogMapper.dailyUsage(any(), any())).thenReturn(List.of());

        AiUsageDtos.Report report = service.report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));

        assertThat(report.startDate()).isEqualTo("2026-10-01");
        assertThat(report.endDate()).isEqualTo("2026-10-03");
        verify(callLogMapper).dailyUsage(
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 4, 0, 0));
    }

    @Test
    @DisplayName("只给结束日期时，起点往前推七天")
    void onlyEndDateInfersStart() {
        when(callLogMapper.dailyUsage(any(), any())).thenReturn(List.of());

        AiUsageDtos.Report report = service.report(null, LocalDate.of(2026, 9, 30));

        assertThat(report.startDate()).isEqualTo("2026-09-24");
        assertThat(report.endDate()).isEqualTo("2026-09-30");
    }

    @Test
    @DisplayName("起始晚于结束应当是参数错误，而不是查出空结果")
    void startAfterEndIsRejected() {
        assertThatThrownBy(() -> service.report(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST)
                .hasMessageContaining("起始日期不能晚于结束日期");
        verify(callLogMapper, never()).dailyUsage(any(), any());
    }

    @Test
    @DisplayName("区间超过上限直接拒绝，避免扫全表")
    void rangeTooLongIsRejected() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = start.plusDays(AiUsageService.MAX_RANGE_DAYS);
        when(callLogMapper.dailyUsage(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.report(start, end))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多 " + AiUsageService.MAX_RANGE_DAYS + " 天");
        // 恰好等于上限时应当放行（边界是"最多 92 天"，不是"少于"）
        assertThatCode(() -> service.report(start, end.minusDays(1))).doesNotThrowAnyException();
        verify(callLogMapper).dailyUsage(any(), any());
    }

    @Test
    @DisplayName("汇总按行累加，total 等于 prompt+completion，缺模型/场景的行显示占位")
    void aggregatesRowsAndLabelsUnknownModelAndScene() {
        when(callLogMapper.dailyUsage(any(), any())).thenReturn(List.of(
                row(LocalDate.of(2026, 10, 2), "CHAT", "deepseek-v4-pro", 100, 20, 80, 20, 3, 0),
                row(LocalDate.of(2026, 10, 2), "", null, 7, 3, 0, 0, 1, 1),
                row(LocalDate.of(2026, 10, 1), "SUMMARY", "deepseek-v4-flash", 50, 10, 0, 50, 2, 0)));

        AiUsageDtos.Report report = service.report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));

        assertThat(report.rows()).hasSize(3);
        assertThat(report.rows().get(1).modelCode()).isEqualTo(AiUsageDtos.UNKNOWN_MODEL);
        assertThat(report.rows().get(1).sceneLabel()).isEqualTo(AiUsageDtos.UNKNOWN_SCENE);
        assertThat(report.rows().get(0).sceneLabel()).isEqualTo("对话（文本）");
        assertThat(report.rows().get(0).totalTokens()).isEqualTo(120);
        assertThat(report.totals().promptTokens()).isEqualTo(157);
        assertThat(report.totals().completionTokens()).isEqualTo(33);
        assertThat(report.totals().totalTokens()).isEqualTo(190);
        assertThat(report.totals().calls()).isEqualTo(6);
        assertThat(report.totals().failedCalls()).isEqualTo(1);
        assertThat(report.totals().cacheHitTokens()).isEqualTo(80);
        assertThat(report.totals().cacheMissTokens()).isEqualTo(70);
        assertThat(report.totals().cacheHitRate()).isEqualTo(0.5333);
        assertThat(report.note()).contains("ai_call_log");
    }

    @Test
    @DisplayName("没有数据时返回零合计与 null 命中率（不是 0）")
    void emptyResultYieldsZeroTotals() {
        when(callLogMapper.dailyUsage(any(), any())).thenReturn(List.of());

        AiUsageDtos.Report report = service.report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));

        assertThat(report.rows()).isEmpty();
        assertThat(report.totals().totalTokens()).isZero();
        assertThat(report.totals().calls()).isZero();
        assertThat(report.totals().cacheHitRate()).isNull();
    }

    @Test
    @DisplayName("有调用但上游没上报命中时，命中率也是 null")
    void rowsWithoutCacheReportHaveNullHitRate() {
        when(callLogMapper.dailyUsage(any(), any())).thenReturn(List.of(
                row(LocalDate.of(2026, 10, 1), "CHAT", "deepseek-v4-pro", 30, 5, 0, 0, 1, 0)));

        AiUsageDtos.Report report = service.report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));

        assertThat(report.rows().get(0).cacheHitRate()).isNull();
        assertThat(report.totals().cacheHitRate()).isNull();
    }

    @Test
    @DisplayName("未知场景编码回显编码本身，而不是占位")
    void unknownSceneCodeIsEchoed() {
        when(callLogMapper.dailyUsage(any(), any())).thenReturn(List.of(
                row(LocalDate.of(2026, 10, 1), "MEAL_ANALYSIS_V2", "deepseek-v4-pro", 10, 1, 0, 0, 1, 0)));

        AiUsageDtos.Report report = service.report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));

        assertThat(report.rows().get(0).sceneLabel()).isEqualTo("MEAL_ANALYSIS_V2");
    }

    private static AiCallUsageRow row(LocalDate day, String sceneCode, String modelCode,
                                      long prompt, long completion, long hit, long miss,
                                      long calls, long failedCalls) {
        AiCallUsageRow row = new AiCallUsageRow();
        row.setUsageDay(day);
        row.setSceneCode(sceneCode);
        row.setModelCode(modelCode);
        row.setPromptTokens(prompt);
        row.setCompletionTokens(completion);
        row.setCacheHitTokens(hit);
        row.setCacheMissTokens(miss);
        row.setCalls(calls);
        row.setFailedCalls(failedCalls);
        return row;
    }
}
