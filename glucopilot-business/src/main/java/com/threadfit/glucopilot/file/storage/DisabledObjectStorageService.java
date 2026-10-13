package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Explicit storage-off mode for deployments without object storage credentials. */
@Service
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "disabled")
public class DisabledObjectStorageService implements ObjectStorageService {

    private BizException unavailable() {
        return new BizException(ErrorCode.BIZ_REJECTED,
                "文件存储未启用，当前环境不支持文件上传与读取");
    }

    @Override
    public UploadCredential createUploadCredential(MediaBizType bizType, String ext, long sizeBytes, Long userId) {
        throw unavailable();
    }

    @Override
    public String resolvePublicUrl(String storageKey) {
        throw unavailable();
    }

    @Override
    public boolean objectExists(String storageKey) {
        throw unavailable();
    }

    @Override
    public byte[] getObject(String storageKey) {
        throw unavailable();
    }

    @Override
    public StorageProvider provider() {
        throw unavailable();
    }

    @Override
    public String bucket() {
        throw unavailable();
    }
}
