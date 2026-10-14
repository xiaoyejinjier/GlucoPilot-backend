package com.threadfit.glucopilot.glucose.record;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.file.service.MediaFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlucoseRecordServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private GlucoseRecordMapper glucoseRecordMapper;
    @Mock
    private GlucosePeriodService glucosePeriodService;
    @Mock
    private MediaFileService mediaFileService;

    @InjectMocks
    private GlucoseRecordService service;

    @Test
    void rejectsMealNode() {
        GlucoseDtos.UpsertRequest request = upsertRequest("breakfast", new BigDecimal("5.0"));
        assertThrows(BizException.class, () -> service.upsertByNode(USER_ID, request));
    }

    @Test
    void rejectsOcrSourceWithoutOriginalImage() {
        GlucoseDtos.UpsertRequest request = upsertRequest("before_breakfast", new BigDecimal("6.2"));
        request.setSourceType(2);
        assertThrows(BizException.class, () -> service.upsertByNode(USER_ID, request));
    }

    @Test
    void rejectsOutOfRangeValue() {
        GlucoseDtos.UpsertRequest request = upsertRequest("before_breakfast", new BigDecimal("40"));
        assertThrows(BizException.class, () -> service.upsertByNode(USER_ID, request));
    }

    @Test
    void insertsBeforeBreakfastWhenAbsent() {
        when(glucoseRecordMapper.selectOne(any())).thenReturn(null);
        when(glucoseRecordMapper.insert(any(GlucoseRecord.class))).thenAnswer(inv -> {
            GlucoseRecord row = inv.getArgument(0);
            row.setId(99L);
            return 1;
        });

        GlucoseDtos.UpsertResult result = service.upsertByNode(USER_ID,
                upsertRequest("before_breakfast", new BigDecimal("6.2")));

        assertTrue(result.getCreated());
        assertEquals("99", result.getId());
        ArgumentCaptor<GlucoseRecord> captor = ArgumentCaptor.forClass(GlucoseRecord.class);
        verify(glucoseRecordMapper).insert(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals("before_breakfast", captor.getValue().getNodeCode());
    }

    @Test
    void updatesWhenPresent() {
        GlucoseRecord existing = record(7L, "before_lunch", new BigDecimal("5.5"));
        when(glucoseRecordMapper.selectOne(any())).thenReturn(existing);

        GlucoseDtos.UpsertResult result = service.upsertByNode(USER_ID,
                upsertRequest("before_lunch", new BigDecimal("6.4")));

        assertFalse(result.getCreated());
        assertEquals("7", result.getId());
        assertEquals(new BigDecimal("6.4"), existing.getGlucoseValue());
        verify(glucoseRecordMapper).updateById(existing);
    }

    @Test
    void recordOfAnotherUserIsNotFound() {
        GlucoseRecord other = record(7L, "before_lunch", new BigDecimal("5.5"));
        other.setUserId(2L);
        when(glucoseRecordMapper.selectById(7L)).thenReturn(other);

        assertThrows(BizException.class, () -> service.getItem(USER_ID, 7L));
    }

    @Test
    void dayBoardMarksOnlyNonMealNodesAsRecorded() {
        when(glucosePeriodService.listEnabledNodes()).thenReturn(List.of(
                node("before_breakfast", "早餐前", 0, 1),
                node("breakfast", "早餐", 1, 2),
                node("after_breakfast", "早餐后", 0, 3)));
        when(glucoseRecordMapper.selectList(any()))
                .thenReturn(List.of(record(1L, "before_breakfast", new BigDecimal("5.8"))));

        GlucoseDtos.DayBoard board = service.dayBoard(USER_ID, LocalDate.of(2026, 9, 15));

        assertEquals(3, board.getTotalNodes());
        assertEquals(1, board.getRecordedCount());
        assertEquals(1, board.getChartPoints().size());
        assertTrue(board.getNodes().get(0).getHasRecord());
        assertEquals("glucose", board.getNodes().get(0).getRecordKind());
        // 餐点节点本期固定未记录
        assertFalse(board.getNodes().get(1).getHasRecord());
        assertEquals(Boolean.TRUE, board.getNodes().get(1).getIsMeal());
    }

    @Test
    void pageMapsNodeNameFromDictionary() {
        GlucoseRecord row = record(3L, "before_breakfast", new BigDecimal("6.0"));
        row.setRecordDate(LocalDate.of(2026, 9, 15));
        when(glucoseRecordMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<GlucoseRecord> page = inv.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1);
            return page;
        });
        when(glucosePeriodService.nodeMap()).thenReturn(java.util.Map.of(
                "before_breakfast", node("before_breakfast", "早餐前", 0, 1)));

        PageResult<GlucoseDtos.RecordItem> result = service.page(USER_ID, null, null, null, new PageQuery());

        assertEquals(1, result.getTotal());
        assertEquals("早餐前", result.getRecords().get(0).getNodeName());
    }

    private static GlucoseDtos.UpsertRequest upsertRequest(String nodeCode, BigDecimal value) {
        GlucoseDtos.UpsertRequest request = new GlucoseDtos.UpsertRequest();
        request.setRecordDate(LocalDate.of(2026, 9, 15));
        request.setNodeCode(nodeCode);
        request.setGlucoseValue(value);
        return request;
    }

    private static GlucoseRecord record(Long id, String nodeCode, BigDecimal value) {
        GlucoseRecord row = new GlucoseRecord();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setRecordDate(LocalDate.of(2026, 9, 15));
        row.setNodeCode(nodeCode);
        row.setGlucoseValue(value);
        return row;
    }

    private static GlucoseNode node(String code, String name, int isMeal, int sortNo) {
        GlucoseNode node = new GlucoseNode();
        node.setNodeCode(code);
        node.setNodeName(name);
        node.setIsMeal(isMeal);
        node.setSortNo(sortNo);
        return node;
    }
}
