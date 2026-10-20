package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 开发环境自带的本地对象存储。
 *
 * <p>仍保持“凭证 → PUT → 登记”协议，只是 PUT 地址由当前后端提供，免去安装 MinIO。
 * 一次性 token 只保存在内存中且 5 分钟过期，生产环境不会创建此 Bean。
 */
@Service
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "local")
public class LocalObjectStorageService implements ObjectStorageService {

    private final Path root;
    private final ConcurrentMap<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();

    public LocalObjectStorageService(StorageProperties properties) {
        this.root = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地上传目录：" + root, e);
        }
    }

    @Override
    public UploadCredential createUploadCredential(MediaBizType bizType, String ext, long sizeBytes, Long userId) {
        Instant now = Instant.now();
        pendingUploads.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        String storageKey = buildStorageKey(bizType, ext, userId);
        String token = UUID.randomUUID().toString().replace("-", "");
        pendingUploads.put(token, new PendingUpload(
                storageKey, sizeBytes, now.plusSeconds(EXPIRE_SECONDS)));
        String uploadUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/media-files/local-uploads/{token}")
                .buildAndExpand(token)
                .toUriString();
        return UploadCredential.builder()
                .uploadUrl(uploadUrl)
                .storageKey(storageKey)
                .expireIn((long) EXPIRE_SECONDS)
                .build();
    }

    /** 消费一次性凭证并落盘；成功或失败后 token 均不能再次使用。 */
    public void acceptUpload(String token, byte[] bytes) {
        PendingUpload pending = pendingUploads.remove(token);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("上传凭证不存在或已过期");
        }
        if (bytes == null || bytes.length != pending.sizeBytes()) {
            throw new IllegalArgumentException("上传文件大小与凭证不一致");
        }
        Path target = resolvePath(pending.storageKey());
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败", e);
        }
    }

    @Override
    public String resolvePublicUrl(String storageKey) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/media-files/local-objects")
                .queryParam("key", storageKey)
                .build()
                .encode()
                .toUriString();
    }

    @Override
    public boolean objectExists(String storageKey) {
        return Files.isRegularFile(resolvePath(storageKey));
    }

    @Override
    public byte[] getObject(String storageKey) {
        try {
            return Files.readAllBytes(resolvePath(storageKey));
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取文件失败");
        }
    }

    /** Newly introduced article drafts must never bypass article visibility via the local preview route. */
    public byte[] getPublicObject(String storageKey) {
        Path relative=root.relativize(resolvePath(storageKey));
        if(relative.getNameCount()==0 || java.util.Set.of("7","8").contains(relative.getName(0).toString())) {
            throw new BizException(ErrorCode.NOT_FOUND,"图片不存在或不可公开查看");
        }
        return getObject(storageKey);
    }

    @Override
    public StorageProvider provider() {
        // 本地存储只用于开发，沿用 MinIO 的开发存储枚举值，避免扩展生产数据库枚举。
        return StorageProvider.MINIO;
    }

    @Override
    public String bucket() {
        // 开发文件没有实际对象存储桶，使用逻辑命名空间兼容媒体登记接口。
        return "local";
    }

    private Path resolvePath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法 storageKey");
        }
        return target;
    }

    private record PendingUpload(String storageKey, long sizeBytes, Instant expiresAt) {
    }
}
