package com.threadfit.glucopilot.file.controller;

import com.threadfit.glucopilot.file.storage.LocalObjectStorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** provider=local 时开放的一次性上传与图片视频读取入口。 */
@RestController
@RequestMapping("/api/v1/media-files")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "local")
public class LocalObjectStorageController {

    private final LocalObjectStorageService storageService;

    /** 请求体从 HttpServletRequest 流式落盘，避免 100MB 视频整段进堆内存。 */
    @PutMapping(value = "/local-uploads/{token}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> upload(@PathVariable String token, HttpServletRequest request) {
        try {
            storageService.acceptUpload(token, request.getInputStream(), request.getContentLengthLong());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/local-objects")
    public ResponseEntity<byte[]> object(@RequestParam String key) {
        byte[] bytes = storageService.getPublicObject(key);
        MediaType mediaType = MediaTypeFactory.getMediaType(key)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(mediaType).body(bytes);
    }
}
