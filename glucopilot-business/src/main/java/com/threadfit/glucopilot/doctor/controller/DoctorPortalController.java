package com.threadfit.glucopilot.doctor.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.consultation.dto.ConsultationDtos.*;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.doctor.dto.DoctorPortalDtos.*;
import com.threadfit.glucopilot.doctor.service.DoctorPortalService;

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
@RequestMapping("/admin/v1/doctor")
@PreAuthorize("@ss.hasRole('doctor')")
public class DoctorPortalController {
    private final DoctorPortalService doctors;
    private final ConsultationService consultations;

    @GetMapping("/workbench")
    @PreAuthorize("@ss.hasRole('doctor') and @ss.hasPermi('doctor:workbench:view')")
    public ResponseEntity<ApiResponse<Workbench>> workbench(@Valid PageQuery page) {
        return ok(doctors.workbench(RequestContext.requireOpsId(), page));
    }

    @GetMapping("/consultations")
    @PreAuthorize("@ss.hasRole('doctor') and @ss.hasPermi('doctor:consultation:manage')")
    public ResponseEntity<ApiResponse<PageResult<CaseView>>> list(@Valid PageQuery page) {
        return ok(
                consultations.list(
                        doctors.doctorId(RequestContext.requireOpsId()), false, true, null, page));
    }

    @GetMapping("/consultations/{id}")
    @PreAuthorize(
            "@ss.hasRole('doctor') and"
                    + " @ss.hasAnyPermi('doctor:workbench:view,doctor:consultation:manage')")
    public ResponseEntity<ApiResponse<CaseView>> detail(@PathVariable @Positive long id) {
        return ok(consultations.detail(doctors.doctorId(RequestContext.requireOpsId()), id));
    }

    @PostMapping("/consultations/{id}/claim")
    @PreAuthorize("@ss.hasRole('doctor') and @ss.hasPermi('doctor:consultation:manage')")
    public ResponseEntity<ApiResponse<CaseView>> claim(@PathVariable @Positive long id) {
        return ok(consultations.claim(doctors.doctorId(RequestContext.requireOpsId()), id));
    }

    @PostMapping("/consultations/{id}/report")
    @PreAuthorize("@ss.hasRole('doctor') and @ss.hasPermi('doctor:consultation:manage')")
    public ResponseEntity<ApiResponse<CaseView>> report(
            @PathVariable @Positive long id, @Valid @RequestBody ReportRequest form) {
        return ok(consultations.report(doctors.doctorId(RequestContext.requireOpsId()), id, form));
    }

    @GetMapping("/income")
    @PreAuthorize("@ss.hasRole('doctor') and @ss.hasPermi('doctor:income:view')")
    public ResponseEntity<ApiResponse<Income>> income(@Valid PageQuery page) {
        return ok(doctors.income(RequestContext.requireOpsId(), page));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
