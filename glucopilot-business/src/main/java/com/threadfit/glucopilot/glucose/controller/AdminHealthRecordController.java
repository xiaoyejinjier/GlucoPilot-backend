package com.threadfit.glucopilot.glucose.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.glucose.admin.AdminHealthRecordDtos;
import com.threadfit.glucopilot.glucose.admin.AdminHealthRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 管理端只读九时段记录看板。 */
@RestController
@RequestMapping("/admin/v1/health-records")
@RequiredArgsConstructor
public class AdminHealthRecordController {

    private final AdminHealthRecordService adminHealthRecordService;

    @GetMapping("/nine-slot")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.HEALTH_ARCHIVE_VIEW + "')")
    public ApiResponse<AdminHealthRecordDtos.Result> nineSlot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery query) {
        return ApiResponse.ok(adminHealthRecordService.query(startDate, endDate, keyword, query));
    }
}
