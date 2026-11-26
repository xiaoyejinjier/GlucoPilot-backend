package com.threadfit.glucopilot.consultation.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.consultation.dto.ConsultationDtos.*;
import com.threadfit.glucopilot.consultation.entity.ConsultationTopic;
import com.threadfit.glucopilot.consultation.entity.DoctorProfile;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.wallet.dto.WalletDtos;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ConsultationController {
    private final ConsultationService service;
    private final WalletOrderService wallets;

    @GetMapping("/consultations/capabilities")
    public ResponseEntity<ApiResponse<Capabilities>> capabilities() {
        RequestContext.requireUserId();
        return ok(service.capabilities());
    }

    @GetMapping("/consultations/topics")
    public ResponseEntity<ApiResponse<List<ConsultationTopic>>> topics() {
        RequestContext.requireUserId();
        return ok(service.topics());
    }

    @GetMapping("/consultations")
    public ResponseEntity<ApiResponse<PageResult<CaseView>>> list(
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "false") boolean assigned,
            @RequestParam(defaultValue = "false") boolean hideMine,
            @RequestParam(defaultValue = "false") boolean unclaimedOnly,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String topic,
            @Valid PageQuery page) {
        return ok(
                service.list(
                        RequestContext.requireUserId(),
                        mine,
                        assigned,
                        hideMine,
                        unclaimedOnly,
                        status,
                        topic,
                        page));
    }

    @GetMapping("/consultations/{id}")
    public ResponseEntity<ApiResponse<CaseView>> detail(@PathVariable @Positive long id) {
        return ok(service.detail(RequestContext.requireUserId(), id));
    }

    @PostMapping("/consultations")
    public ResponseEntity<ApiResponse<CaseView>> publish(
            @Valid @RequestBody PublishRequest request) {
        return ok(service.publish(RequestContext.requireUserId(), request));
    }

    @GetMapping("/consultation-doctors/me")
    public ResponseEntity<ApiResponse<DoctorProfile>> doctor() {
        return ok(service.doctor(RequestContext.requireUserId()));
    }

    @PutMapping("/consultation-doctors/me")
    public ResponseEntity<ApiResponse<DoctorProfile>> apply(
            @Valid @RequestBody DoctorApplication request) {
        return ok(service.apply(RequestContext.requireUserId(), request));
    }

    @PostMapping("/consultations/{id}/claim")
    public ResponseEntity<ApiResponse<CaseView>> claim(@PathVariable @Positive long id) {
        return ok(service.claim(RequestContext.requireUserId(), id));
    }

    @PostMapping("/consultations/{id}/report")
    public ResponseEntity<ApiResponse<CaseView>> report(
            @PathVariable @Positive long id, @Valid @RequestBody ReportRequest request) {
        return ok(service.report(RequestContext.requireUserId(), id, request));
    }

    @GetMapping("/consultation-reports/{id}")
    public ResponseEntity<ApiResponse<ReportView>> report(@PathVariable @Positive long id) {
        return ok(service.reportDetail(RequestContext.requireUserId(), id));
    }

    @PostMapping("/consultation-orders")
    public ResponseEntity<ApiResponse<WalletDtos.Order>> createOrder(
            @Valid @RequestBody OrderRequest request) {
        long user = RequestContext.requireUserId();
        String no = service.createOrder(user, request);
        return ok(wallets.detail(user, false, no));
    }

    @PostMapping("/consultation-orders/{orderNo}/payments")
    public ResponseEntity<ApiResponse<WalletDtos.Order>> pay(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderNo,
            @Valid @RequestBody PaymentRequest request) {
        long user = RequestContext.requireUserId();
        service.pay(user, orderNo);
        return ok(wallets.detail(user, false, orderNo));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
