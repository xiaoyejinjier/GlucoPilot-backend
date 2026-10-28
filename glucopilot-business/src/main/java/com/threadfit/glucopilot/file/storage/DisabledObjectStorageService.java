package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

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
    public long objectSize(String storageKey) {
        throw unavailable();
    }

    @Override
    public InputStream openObject(String storageKey, long offset, long length) {
        throw unavailable();
    }

    /**
     * 存储未启用 = 本环境没有任何对象；删除直接视为成功。
     * <p>不能抛 {@link #unavailable()}：否则注销清理会因为这个环境没配存储而永远重试失败。
     */
    @Override
    public void deleteObject(String storageKey) {
        // no-op
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
