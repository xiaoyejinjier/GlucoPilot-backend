package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.context.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommunityArticleImageController {
    private final CommunityArticleImageService service;
    public CommunityArticleImageController(CommunityArticleImageService service) { this.service=service; }
    @GetMapping("/api/v1/community/article-images/{mediaId}")
    public ResponseEntity<byte[]> image(@PathVariable long mediaId) { return response(service.read(RequestContext.requireUserId(),null,mediaId)); }
    @GetMapping("/admin/v1/community/articles/{id}/images/{mediaId}")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ResponseEntity<byte[]> admin(@PathVariable long id,@PathVariable long mediaId) { return response(service.read(null,id,mediaId)); }
    @GetMapping("/api/v1/community/article-videos/{mediaId}")
    public ResponseEntity<byte[]> video(@PathVariable long mediaId) { return response(service.read(RequestContext.requireUserId(),null,mediaId,true)); }
    @GetMapping("/admin/v1/community/articles/{id}/videos/{mediaId}")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ResponseEntity<byte[]> adminVideo(@PathVariable long id,@PathVariable long mediaId) { return response(service.read(null,id,mediaId,true)); }
    private ResponseEntity<byte[]> response(CommunityArticleImageService.Image image) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType())).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(image.bytes());
    }
}
