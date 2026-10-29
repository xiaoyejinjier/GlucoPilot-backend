package com.threadfit.glucopilot.content.media;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.idempotency.IdempotencyStore;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.MediaItem;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.RegisterRequest;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import com.threadfit.glucopilot.file.vo.FileVos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * CMS 资讯/轮播图/资讯视频素材：走统一对象存储（local / MinIO / OSS），稳定地址不依赖预签名过期。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CmsMediaService {
    private static final long MAX_PIXELS = 16_000_000;
    private static final String BIZ_NEWS = "CONTENT_NEWS";
    private static final String BIZ_BANNER = "CONTENT_BANNER";
    private static final String BIZ_NEWS_VIDEO = "CONTENT_NEWS_VIDEO";
    private static final String MIME_MP4 = "video/mp4";
    private static final String VIDEO_PATH_PREFIX = "/api/v1/cms/videos/";
    private static final Set<String> IMAGE_PURPOSES = Set.of("news", "banner");
    private static final Set<String> ALL_PURPOSES = Set.of("news", "banner", "news-video");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectStorageService storage;
    private final MediaFileMapper mediaFileMapper;
    private final IdempotencyStore idempotencyStore;
    private final JsonUtils jsonUtils;

    /** 按用途筛选；purpose 为空或 all 时返回全部 CMS 素材（图+视频）。 */
    public List<MediaItem> list(String purpose) {
        QueryWrapper<MediaFile> q = cmsMedia().orderByDesc("id");
        if (StringUtils.hasText(purpose) && !"all".equalsIgnoreCase(purpose)) {
            q.eq("biz_id", bizId(normalizePurpose(purpose)));
        }
        return mediaFileMapper.selectList(q).stream().map(this::toItem).toList();
    }

    public FileVos.UploadCredential credentials(UploadCredentialRequest request, Long opsId) {
        String purpose = normalizePurpose(request.getPurpose());
        validate(purpose, request.getBizType(), request.getMimeType(), request.getSizeBytes());
        String ext = extensionOf(request.getMimeType());
        return storage.createUploadCredential(MediaBizType.CMS, ext, request.getSizeBytes(), -opsId);
    }

    @Transactional
    public MediaItem register(RegisterRequest request, Long opsId) {
        // 接口级幂等：透传 X-Idempotency-Key 时先 Redis 占锁再落库，避免并发重复登记。
        String idempotencyKey = RequestContext.getIdempotencyKey();
        if (idempotencyKey == null) {
            return doRegister(request, opsId);
        }
        String snapshotKey = IdempotencyStore.key("cms:register", opsId, idempotencyKey);
        IdempotencyStore.Claim claim = idempotencyStore.claim(snapshotKey);
        if (claim instanceof IdempotencyStore.Claim.Replay replay) {
            return jsonUtils.fromJson(replay.json(), MediaItem.class);
        }
        if (claim instanceof IdempotencyStore.Claim.InFlight) {
            throw new BizException(ErrorCode.CONFLICT, "相同请求处理中，请稍后重试");
        }
        try {
            MediaItem item = doRegister(request, opsId);
            idempotencyStore.complete(snapshotKey, item);
            return item;
        } catch (RuntimeException ex) {
            idempotencyStore.release(snapshotKey);
            throw ex;
        }
    }

    private MediaItem doRegister(RegisterRequest request, Long opsId) {
        String purpose = normalizePurpose(request.getPurpose());
        validate(purpose, request.getBizType(), request.getMimeType(), request.getSizeBytes());
        String key = request.getStorageKey();
        String extension = extensionOf(request.getMimeType());
        if (key == null || !key.matches("5/\\d{4}/\\d{2}/-" + opsId + "/[a-fA-F0-9-]{32,36}\\." + extension)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上传凭证不属于当前账号");
        }
        if (!storage.objectExists(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件尚未上传，请重新上传");
        }

        MediaFile existing = mediaFileMapper.selectOne(cmsMedia()
                .eq("ops_id", opsId).eq("storage_key", key).last("LIMIT 1"));
        if (existing != null) {
            return toItem(existing);
        }

        // 视频最大 100MB：体积用 HEAD 校验，内容用流式读取，避免整段进堆内存。
        long max = MediaFileConstant.cmsMaxBytes(isVideo(request.getMimeType()));
        long actualSize = storage.objectSize(key);
        if (actualSize != request.getSizeBytes() || actualSize > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, "实际文件大小与上传凭证不一致");
        }

        Integer width = null;
        Integer height = null;
        String digest;
        if (isVideo(request.getMimeType())) {
            try (InputStream head = storage.openObject(key, 0, Math.min(actualSize, 64))) {
                inspectMp4(head);
            } catch (IOException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "视频内容读取失败");
            }
            try (InputStream all = storage.openObject(key, 0, actualSize)) {
                digest = sha256(all);
            } catch (IOException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "视频内容读取失败");
            }
        } else {
            byte[] bytes = storage.getObject(key);
            ImageInfo info = inspectImage(bytes, request.getMimeType());
            width = info.width();
            height = info.height();
            digest = sha256(bytes);
        }

        MediaFile row = new MediaFile();
        row.setOpsId(opsId);
        row.setBizType(MediaBizType.CMS.getCode());
        row.setBizId(bizId(purpose));
        row.setStorageProvider(storage.provider().getCode());
        row.setBucketName(storage.bucket());
        row.setStorageKey(key);
        row.setFileUrl("");
        row.setMimeType(request.getMimeType());
        row.setSizeBytes(actualSize);
        row.setWidthPx(width);
        row.setHeightPx(height);
        row.setSha256Hex(digest);
        row.setFileStatus(1);
        mediaFileMapper.insert(row);
        row.setFileUrl(publicPath(row));
        mediaFileMapper.updateById(row);
        return toItem(row);
    }

    @Transactional
    public void remove(Long id, Long opsId) {
        MediaFile row = mediaFileMapper.selectById(id);
        if (row == null || !Objects.equals(row.getBizType(), MediaBizType.CMS.getCode())
                || !isCmsBizId(row.getBizId()) || !Objects.equals(row.getOpsId(), opsId)
                || row.getUserId() != null || !Objects.equals(row.getFileStatus(), 1)) {
            throw new BizException(ErrorCode.NOT_FOUND, "媒体素材不存在");
        }
        row.setFileStatus(0);
        mediaFileMapper.updateById(row);
        // 软删后稳定地址已不可读，及时回收对象（尤其 100MB 视频）；删除失败只记日志，留待离线清理。
        try {
            storage.deleteObject(row.getStorageKey());
        } catch (RuntimeException e) {
            log.warn("CMS 素材对象回收失败 id={} key={}", id, row.getStorageKey(), e);
        }
    }

    public MediaFile requireImage(Long id) {
        MediaFile row = requireCms(id);
        if (!MediaFileConstant.isCmsMimeAllowed(false, row.getMimeType())) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容图片不存在");
        }
        return row;
    }

    public MediaFile requireVideo(Long id) {
        MediaFile row = requireCms(id);
        if (!MIME_MP4.equals(row.getMimeType())) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容视频不存在");
        }
        return row;
    }

    public byte[] read(MediaFile row) {
        return storage.getObject(row.getStorageKey());
    }

    /** 视频对象的字节数（HEAD 查询，不下载内容）。 */
    public long size(MediaFile row) {
        return storage.objectSize(row.getStorageKey());
    }

    /** 打开视频的一段用于流式输出（Range 支持）；调用方负责关闭。 */
    public InputStream openVideo(MediaFile row, long offset, long length) {
        return storage.openObject(row.getStorageKey(), offset, length);
    }

    /**
     * 校验资讯 {@code videoUrl}：空值放行；CMS 稳定路径必须指向已登记且在架的资讯视频；外链仅允许 http(s)。
     * <p>避免管理端把不存在/已下架的 {@code /api/v1/cms/videos/{id}} 或任意协议串写进正文。
     */
    public void validateVideoUrl(String videoUrl) {
        if (!StringUtils.hasText(videoUrl)) {
            return;
        }
        String url = videoUrl.trim();
        if (url.startsWith(VIDEO_PATH_PREFIX)) {
            String idPart = url.substring(VIDEO_PATH_PREFIX.length());
            if (!idPart.matches("\\d{1,18}") || !isNewsVideo(Long.parseLong(idPart))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "视频地址对应的资讯视频不存在或已下架");
            }
            return;
        }
        if (!url.matches("(?i)https?://\\S+")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "视频地址仅支持 http(s) 外链或 /api/v1/cms/videos/{id}");
        }
    }

    private boolean isNewsVideo(Long id) {
        MediaFile row = mediaFileMapper.selectById(id);
        return row != null
                && Objects.equals(row.getBizType(), MediaBizType.CMS.getCode())
                && BIZ_NEWS_VIDEO.equals(row.getBizId())
                && Objects.equals(row.getFileStatus(), 1)
                && row.getUserId() == null
                && row.getOpsId() != null;
    }

    private MediaFile requireCms(Long id) {
        MediaFile row = mediaFileMapper.selectById(id);
        if (row == null || !Objects.equals(row.getBizType(), MediaBizType.CMS.getCode())
                || !isCmsBizId(row.getBizId()) || row.getOpsId() == null || row.getUserId() != null
                || !Objects.equals(row.getFileStatus(), 1)) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容媒体不存在");
        }
        return row;
    }

    private QueryWrapper<MediaFile> cmsMedia() {
        return new QueryWrapper<MediaFile>().eq("biz_type", MediaBizType.CMS.getCode())
                .in("biz_id", BIZ_NEWS, BIZ_BANNER, BIZ_NEWS_VIDEO)
                .eq("file_status", 1).isNull("user_id").isNotNull("ops_id");
    }

    private MediaItem toItem(MediaFile row) {
        return MediaItem.builder()
                .id(String.valueOf(row.getId()))
                .purpose(purposeOf(row.getBizId()))
                .fileUrl(publicPath(row))
                .mimeType(row.getMimeType())
                .sizeBytes(row.getSizeBytes())
                .widthPx(row.getWidthPx())
                .heightPx(row.getHeightPx())
                .uploader(row.getOpsId() == null ? "" : "运营#" + row.getOpsId())
                .createdAt(row.getGmtCreate() == null ? "" : TIME.format(row.getGmtCreate()))
                .build();
    }

    private String publicPath(MediaFile row) {
        if (MIME_MP4.equals(row.getMimeType())) {
            return "/api/v1/cms/videos/" + row.getId();
        }
        return "/api/v1/cms/images/" + row.getId();
    }

    private String normalizePurpose(String purpose) {
        if (!StringUtils.hasText(purpose)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择素材用途：news、banner 或 news-video");
        }
        String normalized = purpose.trim().toLowerCase(Locale.ROOT);
        if (!ALL_PURPOSES.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "素材用途仅支持 news、banner 或 news-video");
        }
        return normalized;
    }

    private String bizId(String purpose) {
        return switch (purpose) {
            case "banner" -> BIZ_BANNER;
            case "news-video" -> BIZ_NEWS_VIDEO;
            default -> BIZ_NEWS;
        };
    }

    private String purposeOf(String bizId) {
        if (BIZ_BANNER.equals(bizId)) {
            return "banner";
        }
        if (BIZ_NEWS_VIDEO.equals(bizId)) {
            return "news-video";
        }
        return "news";
    }

    private boolean isCmsBizId(String bizId) {
        return BIZ_NEWS.equals(bizId) || BIZ_BANNER.equals(bizId) || BIZ_NEWS_VIDEO.equals(bizId);
    }

    private boolean isVideo(String mimeType) {
        return MIME_MP4.equals(mimeType);
    }

    private String extensionOf(String mimeType) {
        if (isVideo(mimeType)) {
            return "mp4";
        }
        if (!MediaFileConstant.isCmsMimeAllowed(false, mimeType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的文件格式");
        }
        return MediaFileConstant.extOf(mimeType);
    }

    private void validate(String purpose, Integer bizType, String mimeType, Long sizeBytes) {
        if (!Objects.equals(bizType, MediaBizType.CMS.getCode()) || mimeType == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择合法的内容媒体文件");
        }
        if ("news-video".equals(purpose)) {
            if (!MediaFileConstant.isCmsMimeAllowed(true, mimeType)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "资讯视频仅支持 MP4（video/mp4）");
            }
            if (sizeBytes == null || sizeBytes <= 0 || sizeBytes > MediaFileConstant.CMS_VIDEO_MAX_BYTES) {
                throw new BizException(ErrorCode.BAD_REQUEST, "视频大小不能超过 100MB");
            }
            return;
        }
        if (!IMAGE_PURPOSES.contains(purpose) || !MediaFileConstant.isCmsMimeAllowed(false, mimeType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择 JPG 或 PNG 格式的内容图片");
        }
        if (sizeBytes == null || sizeBytes <= 0 || sizeBytes > MediaFileConstant.CMS_IMAGE_MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片大小不能超过 5MB");
        }
    }

    /** ISO BMFF：前若干字节含 ASCII "ftyp"；只读对象头部，不加载整段视频。 */
    private void inspectMp4(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(64);
        if (bytes.length < 12) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不是有效的 MP4 文件");
        }
        boolean ftyp = false;
        int limit = Math.min(bytes.length - 4, 64);
        for (int i = 0; i <= limit; i++) {
            if (bytes[i] == 'f' && bytes[i + 1] == 't' && bytes[i + 2] == 'y' && bytes[i + 3] == 'p') {
                ftyp = true;
                break;
            }
        }
        if (!ftyp) {
            throw new BizException(ErrorCode.BAD_REQUEST, "视频内容与 MP4 格式不一致");
        }
    }

    private ImageInfo inspectImage(byte[] bytes, String mimeType) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "无法读取图片内容");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String expected = "image/png".equals(mimeType) ? "png" : "JPEG";
                if (!expected.equalsIgnoreCase(reader.getFormatName())) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "图片内容与文件格式不一致");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "图片尺寸过大，请缩小后上传");
                }
                if (reader.read(0) == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "图片内容损坏");
                }
                return new ImageInfo(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片内容损坏，请重新选择");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 流式计算 SHA-256，不把大对象读进内存。 */
    private String sha256(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record ImageInfo(int width, int height) {
    }
}
