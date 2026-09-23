package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.util.IdUtils;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;

import java.time.LocalDate;

/**
 * 对象存储抽象：凭证 / 直传对象 / 访问 URL。
 *
 * <p>两实现共同契约（不一致即缺陷）：
 * <ul>
 *   <li>{@code storageKey = {bizType}/{yyyy}/{MM}/{userId}/{uuid}.{ext}}</li>
 *   <li>桶私有，禁止匿名下载；{@code fileUrl} 由服务端 {@link #resolvePublicUrl} 生成</li>
 *   <li>落库 provider 值：MinIO=1 / 阿里云OSS=2</li>
 * </ul>
 */
public interface ObjectStorageService {

    /** 预签名 PUT URL 有效期（秒）。 */
    int EXPIRE_SECONDS = 300;

    /** 生成凭证：storageKey + 预签名直传 URL。 */
    UploadCredential createUploadCredential(MediaBizType bizType, String ext, long sizeBytes, Long userId);

    /** 由 storageKey 生成访问 URL（服务端）。 */
    String resolvePublicUrl(String storageKey);

    /** 对象是否已存在（HEAD）。 */
    boolean objectExists(String storageKey);

    /** 当前实现对应的 provider 落库值。 */
    StorageProvider provider();

    /** 生成对象 Key：{bizType}/{yyyy}/{MM}/{userId}/{uuid}.{ext}。 */
    default String buildStorageKey(MediaBizType bizType, String ext, Long userId) {
        LocalDate now = LocalDate.now();
        String yyyy = String.format("%04d", now.getYear());
        String mm = String.format("%02d", now.getMonthValue());
        return bizType.getCode() + "/" + yyyy + "/" + mm + "/" + userId + "/" + IdUtils.uuid() + "." + ext;
    }
}