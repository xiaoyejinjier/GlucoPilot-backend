package com.threadfit.glucopilot.file.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.util.Date;

/**
 * 生产阿里云 OSS（provider=aliyun-oss，matchIfMissing 默认生效）。桶私有，禁止匿名下载。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider",
        havingValue = "aliyun-oss", matchIfMissing = true)
public class AliyunOssObjectStorageService implements ObjectStorageService {

    private final StorageProperties props;
    private final OSS ossClient;

    public AliyunOssObjectStorageService(StorageProperties props, OSS ossClient) {
        this.props = props;
        this.ossClient = ossClient;
    }

    @Override
    public UploadCredential createUploadCredential(MediaBizType bizType, String ext, long sizeBytes, Long userId) {
        String storageKey = buildStorageKey(bizType, ext, userId);
        Date expiration = new Date(System.currentTimeMillis() + EXPIRE_SECONDS * 1000L);
        URL url = ossClient.generatePresignedUrl(props.getBucket(), storageKey, expiration, HttpMethod.PUT);
        return UploadCredential.builder()
                .uploadUrl(url.toString())
                .storageKey(storageKey)
                .expireIn((long) EXPIRE_SECONDS)
                .build();
    }

    @Override
    public String resolvePublicUrl(String storageKey) {
        return "https://" + props.getBucket() + "." + props.getEndpoint() + "/" + storageKey;
    }

    @Override
    public boolean objectExists(String storageKey) {
        return ossClient.doesObjectExist(props.getBucket(), storageKey);
    }

    @Override
    public byte[] getObject(String storageKey) {
        try {
            OSSObject object = ossClient.getObject(props.getBucket(), storageKey);
            try (InputStream in = object.getObjectContent()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            // 读失败必须留下痕迹：这是「图片送模型」链路里最难定位的一环（成功路径无日志）
            log.warn("OSS 读取对象失败 bucket={} key={}", props.getBucket(), storageKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取文件失败");
        }
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.ALIYUN_OSS;
    }
}