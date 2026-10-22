package com.threadfit.glucopilot.file.controller;

import com.threadfit.glucopilot.file.service.AvatarMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media-files/avatars")
@RequiredArgsConstructor
public class AvatarMediaController {
    private final AvatarMediaService avatars;
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> image(@PathVariable long id) {
        var image = avatars.read(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mime()))
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }
}
