package com.threadfit.glucopilot.doctor.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.consultation.controller.ConsultationConversationController;
import com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos.*;
import com.threadfit.glucopilot.consultation.entity.ConsultationMessage;
import com.threadfit.glucopilot.consultation.service.*;
import com.threadfit.glucopilot.doctor.service.DoctorPortalService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/admin/v1/doctor/consultations/{id}")
@PreAuthorize("@ss.hasRole('doctor') and @ss.hasPermi('doctor:consultation:manage')")
public class DoctorCommunicationController {
    private final DoctorPortalService doctors;
    private final ConsultationService service;
    private final ConsultationMediaService media;

    private long viewer() {
        return doctors.doctorId(RequestContext.requireOpsId());
    }

    @GetMapping("/images")
    public ResponseEntity<ApiResponse<List<Photo>>> images(@PathVariable @Positive long id) {
        var rows =
                service.images(viewer(), id).stream()
                        .map(
                                p ->
                                        new Photo(
                                                p.mediaId(),
                                                "/admin/v1/doctor/consultations/"
                                                        + id
                                                        + "/images/"
                                                        + p.mediaId()))
                        .toList();
        return ok(rows);
    }

    @GetMapping("/images/{mediaId}")
    public ResponseEntity<byte[]> image(
            @PathVariable @Positive long id, @PathVariable @Positive long mediaId) {
        return ConsultationConversationController.imageResponse(media.read(viewer(), id, mediaId));
    }

    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<Messages>> messages(
            @PathVariable @Positive long id,
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "30") int pageSize) {
        return ok(service.messages(viewer(), id, afterId, beforeId, pageSize));
    }

    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<ConsultationMessage>> send(
            @PathVariable @Positive long id, @Valid @RequestBody Send form) {
        return ok(service.sendMessage(viewer(), id, form));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
