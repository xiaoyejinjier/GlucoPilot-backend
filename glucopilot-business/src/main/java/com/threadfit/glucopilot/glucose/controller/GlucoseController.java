package com.threadfit.glucopilot.glucose.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** C 端血糖记录、日看板与九时段字典。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GlucoseController {

    private final GlucoseRecordService glucoseRecordService;
    private final GlucosePeriodService glucosePeriodService;

    @PutMapping("/glucose-records/by-node")
    public ApiResponse<GlucoseDtos.UpsertResult> upsertByNode(@Valid @RequestBody GlucoseDtos.UpsertRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(glucoseRecordService.upsertByNode(userId, request));
    }

    @GetMapping("/glucose-records")
    public ApiResponse<PageResult<GlucoseDtos.RecordItem>> records(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String nodeCode,
            @Valid PageQuery query) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(glucoseRecordService.page(userId, startDate, endDate, nodeCode, query));
    }

    @GetMapping("/glucose-records/{id:\\d+}")
    public ApiResponse<GlucoseDtos.RecordItem> recordDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(glucoseRecordService.getItem(RequestContext.requireUserId(), id));
    }

    @PatchMapping("/glucose-records/{id:\\d+}")
    public ApiResponse<GlucoseDtos.RecordItem> patchRecord(
            @PathVariable("id") Long id, @RequestBody GlucoseDtos.PatchRequest request) {
        return ApiResponse.ok(glucoseRecordService.patch(RequestContext.requireUserId(), id, request));
    }

    @DeleteMapping("/glucose-records/{id:\\d+}")
    public ApiResponse<Void> removeRecord(@PathVariable("id") Long id) {
        glucoseRecordService.remove(RequestContext.requireUserId(), id);
        return ApiResponse.ok();
    }

    @GetMapping("/day-boards/{recordDate}")
    public ApiResponse<GlucoseDtos.DayBoard> dayBoard(
            @PathVariable("recordDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate) {
        return ApiResponse.ok(glucoseRecordService.dayBoard(RequestContext.requireUserId(), recordDate));
    }

    /** 九时段字典：匿名可访问，供小程序启动时拉取节点名与餐点标记。 */
    @GetMapping("/dict/glucose-nodes")
    public ApiResponse<List<GlucoseDtos.NodeItem>> glucoseNodes() {
        return ApiResponse.ok(glucosePeriodService.listNodeItems());
    }
}
