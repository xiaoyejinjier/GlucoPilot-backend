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
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** MinIO 实现：通过本后端同源代理接收 PUT，避免浏览器跨域问题。 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "minio")
public class MinioObjectStorageService implements ObjectStorageService {

    private final StorageProperties props;
    private final MinioClient minioClient;
    // 一次性上传凭证：token → 目标 key / 大小 / 过期时间
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
        // 定期清理过期凭证，避免 Map 随运行时间增长
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

    /** 消费一次性凭证并写入 MinIO；凭证校验先于读请求体，大文件不会读完整段才报过期。 */
    public void acceptUpload(String token, InputStream body, long contentLength) {
        // 先 remove 再校验：无论上传成功与否，这个 token 都已作废
        PendingUpload pending = pendingUploads.remove(token);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("上传凭证不存在或已过期");
        }
        if (contentLength >= 0 && contentLength != pending.sizeBytes()) {
            throw new IllegalArgumentException("上传文件大小与凭证不一致");
        }
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(pending.storageKey())
                    // 已知长度时用 stream(流, 大小, -1)，让 SDK 自行决定分片大小
                    .stream(body, pending.sizeBytes(), -1)
                    .contentType("application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw unavailable("write",e);
        }
    }

    /** 对象定位地址，不保证匿名可读；私有资源通过对应业务的后端读取接口展示。 */
    @Override
    public String resolvePublicUrl(String storageKey) {
        return props.getEndpoint() + "/" + props.getBucket() + "/" + storageKey;
    }

    /** 对象缺失与存储故障分别处理，连接失败不能伪装成未上传。 */
    @Override
    public boolean objectExists(String storageKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(storageKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            // 只有明确的 NoSuchKey 才算“不存在”，权限/网络错误必须继续抛出
            if (isMissing(e)) return false;
            throw unavailable("stat",e);
        } catch (Exception e) {
            throw unavailable("stat",e);
        }
    }

    @Override
    public long objectSize(String storageKey) {
        try {
            return minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(storageKey)
                    .build()).size();
        } catch (ErrorResponseException e) {
            if (isMissing(e)) throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
            throw unavailable("stat",e);
        } catch (Exception e) {
            throw unavailable("stat",e);
        }
    }

    @Override
    public InputStream openObject(String storageKey, long offset, long length) {
        try {
            // MinIO 的 offset/length 已是左闭右开，直接传给 SDK
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(storageKey)
                    .offset(offset)
                    .length(length)
                    .build());
        } catch (ErrorResponseException e) {
            if (isMissing(e)) throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
            throw unavailable("read",e);
        } catch (Exception e) {
            throw unavailable("read",e);
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
        // try-with-resources：读完/异常都要关闭底层 HTTP 连接
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket())
                .object(storageKey)
                .build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            if(isMissing(e))throw new BizException(ErrorCode.NOT_FOUND,"文件不存在");
            throw unavailable("read",e);
        } catch (Exception e) {
            throw unavailable("read",e);
        }
    }

    /** removeObject 本身幂等：对象不存在也返回成功，正好符合注销清理“已删即成功”的契约。 */
    @Override
    public void deleteObject(String storageKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(storageKey)
                    .build());
        } catch (Exception e) {
            throw unavailable("delete",e);
        }
    }

    private static boolean isMissing(ErrorResponseException error) {
        // 不同 MinIO/S3 兼容实现的缺失码不一致，这里同时认两种
        return error.errorResponse()!=null&&java.util.Set.of("NoSuchKey","NoSuchObject").contains(error.errorResponse().code());
    }
    private static BizException unavailable(String operation,Exception error) {
        // SDK异常的toString可能附带签名请求头；只记录故障类型及错误码。
        String code=error instanceof ErrorResponseException response&&response.errorResponse()!=null?response.errorResponse().code():error.getClass().getSimpleName();
        log.warn("MinIO {} failed type={} code={}",operation,error.getClass().getSimpleName(),code);
        return new BizException(ErrorCode.INTERNAL_ERROR,"文件存储暂时不可用，请稍后重试");
    }
    private record PendingUpload(String storageKey, long sizeBytes, Instant expiresAt) {}
}
