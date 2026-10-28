package com.threadfit.glucopilot.content.media;

import com.ruoyi.common.annotation.Anonymous;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.MediaItem;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.RegisterRequest;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.vo.FileVos;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
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

    /**
     * 仅公开已登记的 CMS 资讯视频（MP4）。
     * <p>按 {@code Range} 返回 206，播放器可拖动进度且只取所需片段；内容全程流式写出，
     * 不再把 100MB 视频读进堆内存。
     */
    @Anonymous
    @GetMapping("/api/v1/cms/videos/{id:\\d+}")
    public void video(@PathVariable Long id,
                      @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                      HttpServletResponse response) throws IOException {
        var row = cmsMediaService.requireVideo(id);
        long total = cmsMediaService.size(row);
        long start = 0;
        long end = total - 1;
        boolean partial = false;
        if (StringUtils.hasText(rangeHeader)) {
            RangeSpec spec = parseRange(rangeHeader, total);
            if (spec.kind() == RangeKind.UNSATISFIABLE) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + total);
                return;
            }
            if (spec.kind() == RangeKind.PARTIAL) {
                start = spec.start();
                end = spec.end();
                partial = true;
            }
        }
        long length = end - start + 1;
        response.setStatus(partial ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        response.setContentType(MediaType.parseMediaType(row.getMimeType()).toString());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentLengthLong(length);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(Duration.ofHours(1)).cachePublic().getHeaderValue());
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (partial) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }
        try (InputStream in = cmsMediaService.openVideo(row, start, length)) {
            in.transferTo(response.getOutputStream());
        }
    }

    /**
     * 解析单段 Range。
     * <p>按 RFC 7233：多段、语法非法或 end &lt; start 一律忽略 Range（回 200 全量）；
     * 只有语法正确但 {@code start >= total} 才回 416；其余回 206。
     */
    private static RangeSpec parseRange(String header, long total) {
        if (!header.startsWith("bytes=")) {
            return RangeSpec.ignore();
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            // 暂不支持 multipart/byteranges，按规范允许忽略 Range
            return RangeSpec.ignore();
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return RangeSpec.ignore();
        }
        String from = spec.substring(0, dash).trim();
        String to = spec.substring(dash + 1).trim();
        long start;
        long end;
        try {
            if (from.isEmpty()) {
                if (to.isEmpty()) {
                    return RangeSpec.ignore();
                }
                long suffix = Long.parseLong(to);
                if (suffix <= 0) {
                    return RangeSpec.unsatisfiable();
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(from);
                end = to.isEmpty() ? total - 1 : Long.parseLong(to);
            }
        } catch (NumberFormatException e) {
            return RangeSpec.ignore();
        }
        if (start < 0 || end < 0 || end < start) {
            return RangeSpec.ignore();
        }
        if (start >= total) {
            return RangeSpec.unsatisfiable();
        }
        return RangeSpec.partial(start, Math.min(end, total - 1));
    }

    private enum RangeKind { IGNORE, PARTIAL, UNSATISFIABLE }

    private record RangeSpec(RangeKind kind, long start, long end) {
        static RangeSpec ignore() {
            return new RangeSpec(RangeKind.IGNORE, 0, 0);
        }

        static RangeSpec unsatisfiable() {
            return new RangeSpec(RangeKind.UNSATISFIABLE, 0, 0);
        }

        static RangeSpec partial(long start, long end) {
            return new RangeSpec(RangeKind.PARTIAL, start, end);
        }
    }
}
