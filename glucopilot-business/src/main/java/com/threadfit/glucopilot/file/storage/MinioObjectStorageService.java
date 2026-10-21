package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MinIO 开发实现（provider=minio）。
 * <p>浏览器直传预签名 URL 依赖桶 CORS；部分第三方 MinIO 镜像不支持 CORS API。
 * 因此开发态签发<strong>同源后端代理 PUT</strong>（与 local 相同协议），后端再写入 MinIO，
 * 管理端/小程序无需跨域。登记与读取仍走真实 MinIO 对象。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "minio")
public class MinioObjectStorageService implements ObjectStorageService {

    private final StorageProperties props;
    private final MinioClient minioClient;
    private final ConcurrentMap<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();

    public MinioObjectStorageService(StorageProperties props) {
        this.props = props;
        this.minioClient = MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    @Override
    public UploadCredential createUploadCredential(MediaBizType bizType, String ext, long sizeBytes, Long userId) {
        Instant now = Instant.now();
        pendingUploads.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        String storageKey = buildStorageKey(bizType, ext, userId);
        String token = UUID.randomUUID().toString().replace("-", "");
        pendingUploads.put(token, new PendingUpload(storageKey, sizeBytes, now.plusSeconds(EXPIRE_SECONDS)));
        // 同源代理：避免管理端浏览器跨域 PUT 到 :9000（第三方镜像常无 CORS）
        String uploadUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/media-files/minio-uploads/{token}")
                .buildAndExpand(token)
                .toUriString();
        return UploadCredential.builder()
                .uploadUrl(uploadUrl)
                .storageKey(storageKey)
                .expireIn((long) EXPIRE_SECONDS)
                .build();
    }

    /** 消费一次性凭证并写入 MinIO。 */
    public void acceptUpload(String token, byte[] bytes) {
        PendingUpload pending = pendingUploads.remove(token);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("上传凭证不存在或已过期");
        }
        if (bytes == null || bytes.length != pending.sizeBytes()) {
            throw new IllegalArgumentException("上传文件大小与凭证不一致");
        }
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(pending.storageKey())
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/octet-stream")
                    .build());
        } catch (Exception e) {
            log.warn("MinIO putObject 失败 bucket={} key={}", props.getBucket(), pending.storageKey(), e);
            throw new IllegalStateException("写入 MinIO 失败", e);
        }
    }

    @Override
    public String resolvePublicUrl(String storageKey) {
        return props.getEndpoint() + "/" + props.getBucket() + "/" + storageKey;
    }

    @Override
    public boolean objectExists(String storageKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(storageKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            log.debug("MinIO statObject 未命中 bucket={} key={}", props.getBucket(), storageKey, e);
            return false;
        } catch (Exception e) {
            log.warn("MinIO statObject 异常 bucket={} endpoint={} key={}",
                    props.getBucket(), props.getEndpoint(), storageKey, e);
            return false;
        }
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.MINIO;
    }

    @Override
    public String bucket() {
        return props.getBucket();
    }

    @Override
    public byte[] getObject(String storageKey) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket())
                .object(storageKey)
                .build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("MinIO 读取对象失败 bucket={} key={}", props.getBucket(), storageKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取文件失败");
        }
    }

    private record PendingUpload(String storageKey, long sizeBytes, Instant expiresAt) {
    }
}
