package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 开发 MinIO mock（provider=minio）。完整入库流程，不跳过。
 */
@Service
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "minio")
public class MinioObjectStorageService implements ObjectStorageService {

    private final StorageProperties props;
    private final MinioClient minioClient;

    public MinioObjectStorageService(StorageProperties props) {
        this.props = props;
        this.minioClient = MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    @Override
    public UploadCredential createUploadCredential(MediaBizType bizType, String ext, long sizeBytes, Long userId) {
        String storageKey = buildStorageKey(bizType, ext, userId);
        try {
            String uploadUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(props.getBucket())
                            .object(storageKey)
                            .expiry(EXPIRE_SECONDS)
                            .build());
            return UploadCredential.builder()
                    .uploadUrl(uploadUrl)
                    .storageKey(storageKey)
                    .expireIn((long) EXPIRE_SECONDS)
                    .build();
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "生成上传凭证失败");
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
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.MINIO;
    }
}