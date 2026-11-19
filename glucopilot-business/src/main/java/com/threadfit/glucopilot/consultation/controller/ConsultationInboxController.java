package com.threadfit.glucopilot.consultation.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.consultation.entity.ConsultationConversation;
import com.threadfit.glucopilot.consultation.service.ConsultationConversationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ConsultationInboxController {
    private final ConsultationConversationService service;

    public record Read(@NotNull @Positive Long throughId) {}

    @GetMapping("/consultation-conversations")
    public ResponseEntity<ApiResponse<PageResult<ConsultationConversation>>> list(
            @Valid PageQuery page) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.list(RequestContext.requireUserId(), page)));
    }

    @PutMapping("/consultations/{id}/messages/read")
    public ResponseEntity<ApiResponse<Void>> read(
            @PathVariable long id, @Valid @RequestBody Read request) {
        service.read(RequestContext.requireUserId(), id, request.throughId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok());
    }
}
