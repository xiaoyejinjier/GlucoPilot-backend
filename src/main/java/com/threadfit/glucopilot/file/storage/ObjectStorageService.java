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

    /**
     * 读取对象的完整字节（<b>服务端读取</b>，不产生也不依赖公开 URL）。
     * <p>
     * 用途：需要把对象内容本身交给下游处理的场景——当前是「把聊天图片送给视觉模型」。
     * 之所以要有这个方法，是因为另外两条路都走不通：
     * <ul>
     *   <li>把 {@link #resolvePublicUrl} 的结果交给模型服务：开发环境的 endpoint 是
     *       {@code http://localhost:9000}，第三方服务器根本回连不到这台机器；</li>
     *   <li>生产即便是公网可达的 OSS 地址：那也等于把用户健康图片的地址暴露给第三方，
     *       且与本接口「桶私有，禁止匿名下载」的契约相冲突。</li>
     * </ul>
     * 因此图片以 base64 随请求体发送，走服务端读取这条路。
     * <p>
     * <b>本方法不做大小限制</b>：调用方应先用 {@code media_file.size_bytes} 判断再调用，
     * 不要无脑把一个 20MB 的对象读进内存。
     *
     * @param storageKey {@link #buildStorageKey} 生成的对象 Key
     * @return 对象字节
     * @throws com.threadfit.glucopilot.common.exception.BizException 对象不存在或读取失败
     */
    byte[] getObject(String storageKey);

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