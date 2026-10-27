package com.threadfit.glucopilot.food.controller;

import com.ruoyi.common.annotation.Anonymous;
import com.threadfit.glucopilot.food.service.FoodCatalogImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class FoodCatalogImageController {
    private final FoodCatalogImageService service;

    @Anonymous
    @GetMapping("/api/v1/foods/{id:\\d+}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        var image = service.read(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }
}
