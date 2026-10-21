package com.threadfit.glucopilot.content.media;

import com.ruoyi.common.annotation.Anonymous;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.MediaItem;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.RegisterRequest;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.vo.FileVos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 管理端内容媒体（资讯配图 / 轮播图）。
 * <p>上传链路与食物图片一致：先取凭证 → 客户端直传对象存储（local / MinIO / OSS）→ 再登记。
 * 稳定访问地址：图片 {@code /api/v1/cms/images/{id}}，视频 {@code /api/v1/cms/videos/{id}}。
 */
@RestController
@RequiredArgsConstructor
public class CmsMediaController {
    private final CmsMediaService cmsMediaService;

    /** 列出素材；purpose 可选 news / banner / news-video，省略则全部。 */
    @GetMapping("/admin/v1/cms-media")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_MEDIA_MANAGE + "')")
    public ApiResponse<List<MediaItem>> list(@RequestParam(required = false) String purpose) {
        return ApiResponse.ok(cmsMediaService.list(purpose));
    }

    /** 申请直传凭证；bizType 固定为 CMS(5)，用途由 purpose 区分。 */
    @PostMapping("/admin/v1/cms-media/upload-credentials")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_MEDIA_MANAGE + "')")
    public ApiResponse<FileVos.UploadCredential> credentials(
            @Valid @RequestBody UploadCredentialRequest request) {
        return ApiResponse.ok(cmsMediaService.credentials(request, RequestContext.requireOpsId()));
    }

    /** 直传成功后登记元数据，返回稳定相对路径 fileUrl。 */
    @PostMapping("/admin/v1/cms-media")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_MEDIA_MANAGE + "')")
    public ApiResponse<MediaItem> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(cmsMediaService.register(request, RequestContext.requireOpsId()));
    }

    /** 软删除素材（file_status=0），仅允许删除当前运营账号上传的记录。 */
    @DeleteMapping("/admin/v1/cms-media/{id:\\d+}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.CONTENT_MEDIA_MANAGE + "')")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        cmsMediaService.remove(id, RequestContext.requireOpsId());
        return ApiResponse.ok();
    }

    /** 仅公开已登记的 CMS 图片，禁止读取其它私有对象。 */
    @Anonymous
    @GetMapping("/api/v1/cms/images/{id:\\d+}")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        var row = cmsMediaService.requireImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(row.getMimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(cmsMediaService.read(row));
    }

    /** 仅公开已登记的 CMS 资讯视频（MP4）。 */
    @Anonymous
    @GetMapping("/api/v1/cms/videos/{id:\\d+}")
    public ResponseEntity<byte[]> video(@PathVariable Long id) {
        var row = cmsMediaService.requireVideo(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(row.getMimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header("Accept-Ranges", "bytes")
                .body(cmsMediaService.read(row));
    }
}
