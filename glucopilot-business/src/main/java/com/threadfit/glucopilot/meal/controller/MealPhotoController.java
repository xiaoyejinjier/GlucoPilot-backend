package com.threadfit.glucopilot.meal.controller;

import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.meal.service.MealPhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MealPhotoController {
    private final MealPhotoService photos;
    @GetMapping("/api/v1/meal-records/images/{id:[1-9]\\d{0,18}}")
    public ResponseEntity<byte[]> image(@PathVariable long id) {
        var image = photos.read(RequestContext.requireUserId(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mime()))
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }
}
