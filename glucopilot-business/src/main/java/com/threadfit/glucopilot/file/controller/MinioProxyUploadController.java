package com.threadfit.glucopilot.file.controller;

import com.threadfit.glucopilot.file.storage.MinioObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PutMapping(value = "/minio-uploads/{token}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> upload(@PathVariable String token, @RequestBody byte[] bytes) {
        try {
            storageService.acceptUpload(token, bytes);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
