package com.threadfit.glucopilot.food.controller;

import com.ruoyi.common.annotation.Anonymous;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.file.dto.FileDtos;
import com.threadfit.glucopilot.file.vo.FileVos;
import com.threadfit.glucopilot.food.service.FoodImageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class FoodImageController {
    private final FoodImageService foodImageService;

    @GetMapping("/admin/v1/food-images")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<List<FileVos.MediaFileRegisterResult>> list() {
        return ApiResponse.ok(foodImageService.list());
    }

    @PostMapping("/admin/v1/food-images/upload-credentials")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<FileVos.UploadCredential> credentials(
            @Valid @RequestBody FileDtos.UploadCredentialRequest request) {
        return ApiResponse.ok(foodImageService.credentials(request, RequestContext.requireOpsId()));
    }

    @PostMapping("/admin/v1/food-images")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<FileVos.MediaFileRegisterResult> register(
            @Valid @RequestBody FileDtos.RegisterRequest request) {
        return ApiResponse.ok(foodImageService.register(request, RequestContext.requireOpsId()));
    }

    /** 仅公开已登记的食物配图，禁止读取任何 C 端健康照片。 */
    @Anonymous
    @GetMapping("/api/v1/foods/images/{id:\\d+}")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        var row = foodImageService.requireImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(row.getMimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(foodImageService.read(row));
    }
}
