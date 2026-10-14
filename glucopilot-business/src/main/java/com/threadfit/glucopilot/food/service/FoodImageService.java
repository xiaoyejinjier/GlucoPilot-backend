package com.threadfit.glucopilot.food.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.dto.FileDtos;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import com.threadfit.glucopilot.file.vo.FileVos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 营养库公开配图与用户健康照片分开登记，下载地址不依赖存储签名期限。 */
@Service
@RequiredArgsConstructor
public class FoodImageService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;
    private static final String BIZ_ID = "FOOD_IMAGE";
    private static final Map<String, String> FORMATS = Map.of("image/jpeg", "jpg", "image/png", "png");

    private final ObjectStorageService storage;
    private final MediaFileMapper mediaFileMapper;

    public List<FileVos.MediaFileRegisterResult> list() {
        return mediaFileMapper.selectList(foodImages().orderByDesc("id"))
                .stream().map(this::toResult).toList();
    }

    public FileVos.UploadCredential credentials(FileDtos.UploadCredentialRequest request, Long opsId) {
        validate(request.getBizType(), request.getMimeType(), request.getSizeBytes());
        // 负数仅用于对象 Key，与 C 端用户的正数 Key 隔离，不写入用户外键。
        return storage.createUploadCredential(MediaBizType.CMS,
                FORMATS.get(request.getMimeType()), request.getSizeBytes(), -opsId);
    }

    @Transactional
    public FileVos.MediaFileRegisterResult register(FileDtos.RegisterRequest request, Long opsId) {
        validate(request.getBizType(), request.getMimeType(), request.getSizeBytes());
        String key = request.getStorageKey();
        String extension = FORMATS.get(request.getMimeType());
        if (key == null || !key.matches("5/\\d{4}/\\d{2}/-" + opsId + "/[a-fA-F0-9-]{32,36}\\." + extension)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片上传凭证不属于当前账号");
        }
        if (!storage.objectExists(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片尚未上传，请重新上传");
        }

        MediaFile existing = mediaFileMapper.selectOne(foodImages()
                .eq("ops_id", opsId).eq("storage_key", key).last("LIMIT 1"));
        if (existing != null) return toResult(existing);

        byte[] bytes = storage.getObject(key);
        if (bytes.length != request.getSizeBytes() || bytes.length > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "实际图片大小与上传凭证不一致");
        }
        ImageInfo info = inspectImage(bytes, request.getMimeType());
        MediaFile row = new MediaFile();
        row.setOpsId(opsId);
        row.setBizType(MediaBizType.CMS.getCode());
        row.setBizId(BIZ_ID);
        row.setStorageProvider(storage.provider().getCode());
        row.setStorageKey(key);
        // file_url 为非空列，先插入再用数据库生成的 ID 建立永久资源地址。
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
        return toResult(row);
    }

    public MediaFile requireImage(Long id) {
        MediaFile row = mediaFileMapper.selectById(id);
        if (row == null || !Objects.equals(row.getBizType(), MediaBizType.CMS.getCode())
                || !BIZ_ID.equals(row.getBizId()) || row.getOpsId() == null || row.getUserId() != null
                || !Objects.equals(row.getFileStatus(), 1) || row.getMimeType() == null
                || !FORMATS.containsKey(row.getMimeType())) {
            throw new BizException(ErrorCode.NOT_FOUND, "食物图片不存在");
        }
        return row;
    }

    public byte[] read(MediaFile row) {
        return storage.getObject(row.getStorageKey());
    }

    private QueryWrapper<MediaFile> foodImages() {
        return new QueryWrapper<MediaFile>().eq("biz_type", MediaBizType.CMS.getCode())
                .eq("biz_id", BIZ_ID).eq("file_status", 1).isNull("user_id").isNotNull("ops_id");
    }

    private FileVos.MediaFileRegisterResult toResult(MediaFile row) {
        return FileVos.MediaFileRegisterResult.builder()
                .id(String.valueOf(row.getId())).fileUrl(imagePath(row.getId())).build();
    }

    private String imagePath(Long id) {
        return "/api/v1/foods/images/" + id;
    }

    private void validate(Integer bizType, String mimeType, Long sizeBytes) {
        if (!Objects.equals(bizType, MediaBizType.CMS.getCode())
                || mimeType == null || !FORMATS.containsKey(mimeType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择 JPG 或 PNG 格式的食物图片");
        }
        if (sizeBytes == null || sizeBytes <= 0 || sizeBytes > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片大小不能超过 5MB");
        }
    }

    private ImageInfo inspectImage(byte[] bytes, String mimeType) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BizException(ErrorCode.BAD_REQUEST, "无法读取图片内容");
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
                // 检查内容能实际解码，防止仅有文件头的损坏图片登记成功。
                if (reader.read(0) == null) throw new BizException(ErrorCode.BAD_REQUEST, "图片内容损坏");
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

    private record ImageInfo(int width, int height) { }
}
