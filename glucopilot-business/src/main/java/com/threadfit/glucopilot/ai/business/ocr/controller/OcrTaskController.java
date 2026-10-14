package com.threadfit.glucopilot.ai.business.ocr.controller;

import com.threadfit.glucopilot.ai.business.ocr.dto.OcrDtos.CreateRequest;
import com.threadfit.glucopilot.ai.business.ocr.dto.OcrDtos.TaskView;
import com.threadfit.glucopilot.ai.business.ocr.service.OcrTaskService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ocr-tasks")
@RequiredArgsConstructor
public class OcrTaskController {

    private final OcrTaskService ocrTaskService;

    @PostMapping
    public ApiResponse<TaskView> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(ocrTaskService.create(request, RequestContext.requireUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskView> detail(@PathVariable Long id) {
        return ApiResponse.ok(ocrTaskService.detail(id, RequestContext.requireUserId()));
    }
}
