package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * MinIO 开发实现（provider=minio，本地 mock 对象存储）。
 * <p>完整走「凭证 → 直传 → 登记 → 读取」全链路，不因是 mock 就跳过任何校验环节，
 * 保证开发环境与生产 OSS 行为一致。直传使用预签名 PUT URL（有效 {@link ObjectStorageService#EXPIRE_SECONDS} 秒）。
 * <p><b>注意</b>：签发预签名 URL 时刻意不携带 Content-Type——
 * 真正的 MIME 校验推迟到登记接口 {@code MediaFileService#register} 进行，
 * 避免前端在「预签名阶段」通过 Content-Type 绕过白名单。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "minio")
public class MinioObjectStorageService implements ObjectStorageService {

    private final StorageProperties props;
    private final MinioClient minioClient;

    public MinioObjectStorageService(StorageProperties props) {
        this.props = props;
        // 按配置初始化 MinIO 客户端（endpoint / AK / SK）
        this.minioClient = MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    /** 生成 storageKey 并签发预签名 PUT URL 供客户端直传；客户端直传后凭同一 storageKey 走登记。 */
    @Override
    public UploadCredential createUploadCredential(MediaBizType bizType, String ext, long sizeBytes, Long userId) {
        // storageKey = {bizType}/{yyyy}/{MM}/{userId}/{uuid}.{ext}，第 3 段 userId 供登记时防伪
        String storageKey = buildStorageKey(bizType, ext, userId);
        try {
            // 签发 PUT 直传 URL，有效期 EXPIRE_SECONDS(300s)
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

    /** 由 storageKey 拼出公开访问 URL；桶默认私有，这里仅为 C 端展示用。 */
    @Override
    public String resolvePublicUrl(String storageKey) {
        return props.getEndpoint() + "/" + props.getBucket() + "/" + storageKey;
    }

    /** HEAD 探活：登记接口据此确认真实上传。异常一律视为「不存在」，转入拒绝分支。 */
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

    /** 服务端读取对象完整字节（用于「图片送模型」场景，不产生公开 URL）。读失败必须留痕。 */
    @Override
    public byte[] getObject(String storageKey) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket())
                .object(storageKey)
                .build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            // 读失败必须留下痕迹：这是「图片送模型」链路里最难定位的一环（成功路径无日志）
            log.warn("MinIO 读取对象失败 bucket={} key={}", props.getBucket(), storageKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取文件失败");
        }
    }
}