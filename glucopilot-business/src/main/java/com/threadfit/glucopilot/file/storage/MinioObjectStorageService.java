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
 * MinIO 对象存储实现（provider=minio，可连接团队共享服务）。
 * <p>完整走「凭证 → 直传 → 登记 → 读取」全链路，
 * 保持与 OSS 相同的业务校验。直传使用预签名 PUT URL（有效 {@link ObjectStorageService#EXPIRE_SECONDS} 秒）。
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
            // 必须留痕：这里失败的真实原因通常是「MinIO 容器没起 / 端口不通 / 桶不存在」，
            // 而对外只能给一句「生成上传凭证失败」。若没有日志，现场就只剩那句话，
            // 排查会直接往错的方向找（本项目真实发生过：容器没启动，却被当成存储代码有问题）。
            log.warn("MinIO 生成上传凭证失败 bucket={} endpoint={} key={}",
                    props.getBucket(), props.getEndpoint(), storageKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "生成上传凭证失败");
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
            if (isMissing(e)) return false;
            throw unavailable("stat",e);
        } catch (Exception e) {
            throw unavailable("stat",e);
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

    /** 服务端读取对象完整字节（用于「图片送模型」场景，不产生公开 URL）。读失败必须留痕。 */
    @Override
    public byte[] getObject(String storageKey) {
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

    private static boolean isMissing(ErrorResponseException error) {
        return error.errorResponse()!=null&&java.util.Set.of("NoSuchKey","NoSuchObject").contains(error.errorResponse().code());
    }
    private static BizException unavailable(String operation,Exception error) {
        // SDK异常的toString可能附带签名请求头；只记录故障类型及错误码。
        String code=error instanceof ErrorResponseException response&&response.errorResponse()!=null?response.errorResponse().code():error.getClass().getSimpleName();
        log.warn("MinIO {} failed type={} code={}",operation,error.getClass().getSimpleName(),code);
        return new BizException(ErrorCode.INTERNAL_ERROR,"文件存储暂时不可用，请稍后重试");
    }
}
