package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.context.RequestContext;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommunityVideoCoverController {
    private final CommunityVideoCoverService covers;
    public CommunityVideoCoverController(CommunityVideoCoverService covers) { this.covers = covers; }

    @GetMapping("/api/v1/community/article-videos/{mediaId}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable long mediaId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff")
                .body(covers.read(RequestContext.requireUserId(),mediaId));
    }
}
