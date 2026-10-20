package com.threadfit.glucopilot.food.controller;

import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.food.service.FoodReviewImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class FoodReviewImageController {
    private final FoodReviewImageService service;

    @GetMapping("/admin/v1/foods/{id:\\d+}/review-image")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        var image = service.read(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }
}
