package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.util.IdUtils;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;

import java.io.InputStream;
import java.time.LocalDate;

/**
 * 对象存储抽象：生成直传凭证、校验对象、读取对象、生成访问地址。
 * <p>共同契约：storageKey 必须带 userId；桶私有；fileUrl 只由服务端生成。
 */
public interface ObjectStorageService {

    /** 预签名 PUT URL 有效期（秒）：够传完一个文件，又能缩短凭证泄露窗口。 */
    int EXPIRE_SECONDS = 300;

    /** 生成凭证：storageKey + 预签名直传 URL。 */
    UploadCredential createUploadCredential(MediaBizType bizType, String ext, long sizeBytes, Long userId);

    /** 由 storageKey 生成访问 URL（服务端）。 */
    String resolvePublicUrl(String storageKey);

    /** 对象是否已存在（HEAD）。 */
    boolean objectExists(String storageKey);

    /**
     * 服务端读取完整字节（当前用于把聊天图片送给视觉模型）。
     * <p>模型服务无法回连本地 MinIO，且不能把私有桶地址暴露给第三方，所以以字节/base64 随请求发送。
     * <p>本方法不限制大小：调用前先用 {@link #objectSize} 判断，避免把 20MB 对象读进内存。
     */
    byte[] getObject(String storageKey);

    /** HEAD 查询对象字节数，不下载内容：用于 100MB 视频先校验体积。 */
    long objectSize(String storageKey);

    /**
     * 打开对象的 [offset, offset+length) 区段，供视频 Range 播放、大对象流式哈希使用。
     * <p>不把内容整体读进堆内存；返回的流由调用方负责关闭。
     */
    InputStream openObject(String storageKey, long offset, long length);

    /** 删除对象（注销清理用）。幂等：对象不存在视为成功，只有真正删除失败才抛出。 */
    void deleteObject(String storageKey);

    /** 落库值：本地开发与 MinIO=1，阿里云 OSS=2。 */
    StorageProvider provider();

    /** 当前桶名，落库到 media_file.bucket_name。 */
    String bucket();

    /** 生成对象 Key：{bizType}/{yyyy}/{MM}/{userId}/{uuid}.{ext}。 */
    default String buildStorageKey(MediaBizType bizType, String ext, Long userId) {
        // userId 放在路径中段：登记媒体时用它做前缀归属校验，防止代登记他人文件
        LocalDate now = LocalDate.now();
        String yyyy = String.format("%04d", now.getYear());
        String mm = String.format("%02d", now.getMonthValue());
        return bizType.getCode() + "/" + yyyy + "/" + mm + "/" + userId + "/" + IdUtils.uuid() + "." + ext;
    }
}
