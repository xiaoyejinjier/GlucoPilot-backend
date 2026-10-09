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
 * 文件上传控制器（C 端）：封装「两步契约」的对外 HTTP 入口。
 * <p>链路为 {@code POST /upload-credentials(取预签名URL) → 客户端直传对象存储 → POST /(登记)}：
 * 文件本体不经后端传输，后端只负责签发直传凭证与核对登记结果。
 * <p>用户身份一律取自 {@link RequestContext}（由网关/拦截器解析），不透传、不信任客户端传入；
 * 两个入参 DTO 均走 JSR-303 校验（{@code @Valid} + 注解）。</p>
 */
@RestController
@RequestMapping("/api/v1/media-files")
@RequiredArgsConstructor
public class MediaFileController {

    private final MediaFileService mediaFileService;

    /** 第一步：签发预签名 PUT URL + 目标 storageKey（有效期 300s），客户端凭此直传对象存储。 */
    @PostMapping("/upload-credentials")
    public ApiResponse<UploadCredential> uploadCredentials(@Valid @RequestBody UploadCredentialRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mediaFileService.createUploadCredential(request, userId));
    }

    /** 第二步：客户端直传完成后登记，服务端校验真实性并落库，返回正式 fileUrl。 */
    @PostMapping
    public ApiResponse<MediaFileRegisterResult> register(@Valid @RequestBody RegisterRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mediaFileService.register(request, userId));
    }
}