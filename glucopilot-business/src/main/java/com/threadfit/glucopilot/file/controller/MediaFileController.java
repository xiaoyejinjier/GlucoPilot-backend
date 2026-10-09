package com.threadfit.glucopilot.file.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.file.dto.FileDtos.RegisterRequest;
import com.threadfit.glucopilot.file.dto.FileDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.service.MediaFileService;
import com.threadfit.glucopilot.file.vo.FileVos.MediaFileRegisterResult;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件上传（C 端）：两步流程 凭证 → 直传 → 登记。
 */
@RestController
@RequestMapping("/api/v1/media-files")
@RequiredArgsConstructor
public class MediaFileController {

    private final MediaFileService mediaFileService;

    @PostMapping("/upload-credentials")
    public ApiResponse<UploadCredential> uploadCredentials(@Valid @RequestBody UploadCredentialRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mediaFileService.createUploadCredential(request, userId));
    }

    @PostMapping
    public ApiResponse<MediaFileRegisterResult> register(@Valid @RequestBody RegisterRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mediaFileService.register(request, userId));
    }
}