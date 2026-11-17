package com.threadfit.glucopilot.consultation.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.consultation.dto.ConsultationDtos.DoctorReview;
import com.threadfit.glucopilot.consultation.entity.DoctorProfile;
import com.threadfit.glucopilot.consultation.service.ConsultationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/admin/v1/consultation-doctors")
@PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('doctor:review:manage')")
public class AdminConsultationController {
    private final ConsultationService service;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<DoctorProfile>>> list(
            @RequestParam(required = false) String status, @Valid PageQuery page) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.doctors(status, page)));
    }

    @PostMapping("/{userId}/review")
    public ApiResponse<DoctorProfile> review(
            @PathVariable @Positive long userId, @Valid @RequestBody DoctorReview request) {
        return ApiResponse.ok(service.review(RequestContext.requireOpsId(), userId, request));
    }
}
