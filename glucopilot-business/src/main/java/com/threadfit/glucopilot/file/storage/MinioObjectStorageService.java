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
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 开发 MinIO mock（provider=minio）。完整入库流程，不跳过。
 */
@Slf4j
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
            // 必须留痕：这里失败的真实原因通常是「MinIO 容器没起 / 端口不通 / 桶不存在」，
            // 而对外只能给一句「生成上传凭证失败」。若没有日志，现场就只剩那句话，
            // 排查会直接往错的方向找（本项目真实发生过：容器没启动，却被当成存储代码有问题）。
            log.warn("MinIO 生成上传凭证失败 bucket={} endpoint={} key={}",
                    props.getBucket(), props.getEndpoint(), storageKey, e);
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
        } catch (ErrorResponseException e) {
            // 「对象不存在」是正常业务结果（2.15 登记校验时经常命中），压到 DEBUG 免得刷屏
            log.debug("MinIO statObject 未命中 bucket={} key={}", props.getBucket(), storageKey, e);
            return false;
        } catch (Exception e) {
            // 连不上 MinIO 也会走到这里，而本方法只能返回 false。
            // 于是「存储不可达」在上层会被解释成「你传的文件不存在」——方向完全错，
            // 所以这一类必须留下 WARN，否则真实原因不可见。
            log.warn("MinIO statObject 异常（存储不可达？）bucket={} endpoint={} key={}",
                    props.getBucket(), props.getEndpoint(), storageKey, e);
            return false;
        }
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.MINIO;
    }

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