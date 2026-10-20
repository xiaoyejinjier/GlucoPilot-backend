package com.threadfit.glucopilot.ai.business.speech;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SpeechTranscriptionController {
    private final SpeechTranscriptionService service;

    @PostMapping("/api/v1/speech-transcriptions")
    public ApiResponse<SpeechDtos.Result> transcribe(@Valid @RequestBody SpeechDtos.Request request) {
        return ApiResponse.ok(service.transcribe(RequestContext.requireUserId(), request));
    }
}
