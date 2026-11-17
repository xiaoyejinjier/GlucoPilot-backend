package com.threadfit.glucopilot.consultation.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos.*;
import com.threadfit.glucopilot.consultation.entity.ConsultationMessage;
import com.threadfit.glucopilot.consultation.service.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ConsultationConversationController {
    private final ConsultationService service;
    private final ConsultationMediaService media;

    @GetMapping("/consultations/{id}/images")
    public ResponseEntity<ApiResponse<List<Photo>>> images(@PathVariable @Positive long id) {
        return ok(service.images(RequestContext.requireUserId(), id));
    }

    @GetMapping("/consultations/{id}/images/{mediaId}")
    public ResponseEntity<byte[]> image(
            @PathVariable @Positive long id, @PathVariable @Positive long mediaId) {
        return imageResponse(media.read(RequestContext.requireUserId(), id, mediaId));
    }

    @GetMapping("/consultation-media/{mediaId}")
    public ResponseEntity<byte[]> own(@PathVariable @Positive long mediaId) {
        return imageResponse(media.read(RequestContext.requireUserId(), null, mediaId));
    }

    @GetMapping("/consultations/{id}/messages")
    public ResponseEntity<ApiResponse<Messages>> messages(
            @PathVariable @Positive long id,
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "30") int pageSize) {
        return ok(
                service.messages(RequestContext.requireUserId(), id, afterId, beforeId, pageSize));
    }

    @PostMapping("/consultations/{id}/messages")
    public ResponseEntity<ApiResponse<ConsultationMessage>> send(
            @PathVariable @Positive long id, @Valid @RequestBody Send form) {
        return ok(service.sendMessage(RequestContext.requireUserId(), id, form));
    }

    public static ResponseEntity<byte[]> imageResponse(ConsultationMediaService.Image image) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(image.mime()))
                .body(image.bytes());
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
