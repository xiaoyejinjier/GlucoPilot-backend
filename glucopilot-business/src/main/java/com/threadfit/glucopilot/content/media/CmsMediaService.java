package com.threadfit.glucopilot.content.media;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.MediaItem;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.RegisterRequest;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import com.threadfit.glucopilot.file.vo.FileVos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CMS 资讯/轮播图素材：走统一对象存储（local / MinIO / OSS），稳定地址不依赖预签名过期。
 */
@Service
@RequiredArgsConstructor
public class CmsMediaService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;
    private static final String BIZ_NEWS = "CONTENT_NEWS";
    private static final String BIZ_BANNER = "CONTENT_BANNER";
    private static final Set<String> PURPOSES = Set.of("news", "banner");
    private static final Map<String, String> FORMATS = Map.of("image/jpeg", "jpg", "image/png", "png");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectStorageService storage;
    private final MediaFileMapper mediaFileMapper;

    /** 按用途筛选可用素材；purpose 为空或 all 时返回全部 CMS 内容图。 */
    public List<MediaItem> list(String purpose) {
        QueryWrapper<MediaFile> q = cmsImages().orderByDesc("id");
        if (StringUtils.hasText(purpose) && !"all".equalsIgnoreCase(purpose)) {
            q.eq("biz_id", bizId(purpose));
        }
        return mediaFileMapper.selectList(q).stream().map(this::toItem).toList();
    }

    /** 签发直传凭证；对象 Key 第三段为负的 opsId，与 C 端用户 Key 隔离。 */
    public FileVos.UploadCredential credentials(UploadCredentialRequest request, Long opsId) {
        normalizePurpose(request.getPurpose());
        validate(request.getBizType(), request.getMimeType(), request.getSizeBytes());
        // 负数仅用于对象 Key，与 C 端用户的正数 Key 隔离，不写入用户外键。
        return storage.createUploadCredential(MediaBizType.CMS,
                FORMATS.get(request.getMimeType()), request.getSizeBytes(), -opsId);
    }

    /** 校验归属与真实图片后写入 media_file，fileUrl 使用稳定公开路径。 */
    @Transactional
    public MediaItem register(RegisterRequest request, Long opsId) {
        String purpose = normalizePurpose(request.getPurpose());
        validate(request.getBizType(), request.getMimeType(), request.getSizeBytes());
        String key = request.getStorageKey();
        String extension = FORMATS.get(request.getMimeType());
        if (key == null || !key.matches("5/\\d{4}/\\d{2}/-" + opsId + "/[a-fA-F0-9-]{32,36}\\." + extension)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片上传凭证不属于当前账号");
        }
        if (!storage.objectExists(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片尚未上传，请重新上传");
        }

        MediaFile existing = mediaFileMapper.selectOne(cmsImages()
                .eq("ops_id", opsId).eq("storage_key", key).last("LIMIT 1"));
        if (existing != null) {
            return toItem(existing);
        }

        byte[] bytes = storage.getObject(key);
        if (bytes.length != request.getSizeBytes() || bytes.length > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "实际图片大小与上传凭证不一致");
        }
        ImageInfo info = inspectImage(bytes, request.getMimeType());
        MediaFile row = new MediaFile();
        row.setOpsId(opsId);
        row.setBizType(MediaBizType.CMS.getCode());
        row.setBizId(bizId(purpose));
        row.setStorageProvider(storage.provider().getCode());
        row.setBucketName(storage.bucket());
        row.setStorageKey(key);
        row.setFileUrl("");
        row.setMimeType(request.getMimeType());
        row.setSizeBytes((long) bytes.length);
        row.setWidthPx(info.width());
        row.setHeightPx(info.height());
        row.setSha256Hex(sha256(bytes));
        row.setFileStatus(1);
        mediaFileMapper.insert(row);
        row.setFileUrl(imagePath(row.getId()));
        mediaFileMapper.updateById(row);
        return toItem(row);
    }

    /** 软删除：仅标记不可用，不在此处物理删 MinIO/OSS 对象。 */
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
    }

    public MediaFile requireImage(Long id) {
        MediaFile row = mediaFileMapper.selectById(id);
        if (row == null || !Objects.equals(row.getBizType(), MediaBizType.CMS.getCode())
                || !isCmsBizId(row.getBizId()) || row.getOpsId() == null || row.getUserId() != null
                || !Objects.equals(row.getFileStatus(), 1) || row.getMimeType() == null
                || !FORMATS.containsKey(row.getMimeType())) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容图片不存在");
        }
        return row;
    }

    public byte[] read(MediaFile row) {
        return storage.getObject(row.getStorageKey());
    }

    private QueryWrapper<MediaFile> cmsImages() {
        return new QueryWrapper<MediaFile>().eq("biz_type", MediaBizType.CMS.getCode())
                .in("biz_id", BIZ_NEWS, BIZ_BANNER)
                .eq("file_status", 1).isNull("user_id").isNotNull("ops_id");
    }

    private MediaItem toItem(MediaFile row) {
        return MediaItem.builder()
                .id(String.valueOf(row.getId()))
                .purpose(purposeOf(row.getBizId()))
                .fileUrl(imagePath(row.getId()))
                .mimeType(row.getMimeType())
                .sizeBytes(row.getSizeBytes())
                .widthPx(row.getWidthPx())
                .heightPx(row.getHeightPx())
                .uploader(row.getOpsId() == null ? "" : "运营#" + row.getOpsId())
                .createdAt(row.getGmtCreate() == null ? "" : TIME.format(row.getGmtCreate()))
                .build();
    }

    private String imagePath(Long id) {
        return "/api/v1/cms/images/" + id;
    }

    private String normalizePurpose(String purpose) {
        if (!StringUtils.hasText(purpose)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择素材用途：news 或 banner");
        }
        String normalized = purpose.trim().toLowerCase(Locale.ROOT);
        if (!PURPOSES.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "素材用途仅支持 news 或 banner");
        }
        return normalized;
    }

    private String bizId(String purpose) {
        return "banner".equals(purpose) ? BIZ_BANNER : BIZ_NEWS;
    }

    private String purposeOf(String bizId) {
        return BIZ_BANNER.equals(bizId) ? "banner" : "news";
    }

    private boolean isCmsBizId(String bizId) {
        return BIZ_NEWS.equals(bizId) || BIZ_BANNER.equals(bizId);
    }

    private void validate(Integer bizType, String mimeType, Long sizeBytes) {
        if (!Objects.equals(bizType, MediaBizType.CMS.getCode())
                || mimeType == null || !FORMATS.containsKey(mimeType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择 JPG 或 PNG 格式的内容图片");
        }
        if (sizeBytes == null || sizeBytes <= 0 || sizeBytes > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片大小不能超过 5MB");
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

    private record ImageInfo(int width, int height) {
    }
}
