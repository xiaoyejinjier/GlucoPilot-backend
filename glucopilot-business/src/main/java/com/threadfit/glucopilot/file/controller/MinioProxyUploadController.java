package com.threadfit.glucopilot.file.controller;

import com.threadfit.glucopilot.file.storage.MinioObjectStorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * provider=minio 时的同源一次性上传入口（浏览器 PUT 到后端，再写入 MinIO）。
 * 避免依赖桶 CORS（部分第三方 MinIO 镜像不支持 CORS API）。
 */
@RestController
@RequestMapping("/api/v1/media-files")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "minio")
public class MinioProxyUploadController {

    private final MinioObjectStorageService storageService;

    /**
     * 请求体流式写入 MinIO，避免 100MB 视频整段进堆内存。
     * <p>存储写失败由 acceptUpload 统一包成 {@code BizException(INTERNAL_ERROR)}，
     * 经全局异常处理器回 500 + 友好文案，本控制器不单独捕获（避免裸 500 丢失错误信息）。
     */
    @PutMapping(value = "/minio-uploads/{token}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> upload(@PathVariable String token, HttpServletRequest request) {
        try {
            storageService.acceptUpload(token, request.getInputStream(), request.getContentLengthLong());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
