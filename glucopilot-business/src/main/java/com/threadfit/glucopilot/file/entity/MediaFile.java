package com.threadfit.glucopilot.file.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 媒体文件元数据（media_file，见 V1 L398-434）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("media_file")
public class MediaFile extends BaseEntity {

    /** C 端上传用户，可空。 */
    private Long userId;

    /** 运营上传，可空。 */
    private Long opsId;

    /** 业务类型（CodeEnum.MediaBizType）。 */
    private Integer bizType;

    /** 业务主键（如资讯ID），便于素材复用与清理。 */
    private String bizId;

    /** 存储 provider（CodeEnum.StorageProvider：1=MinIO 2=阿里云OSS）。 */
    private Integer storageProvider;

    /** 桶名。 */
    private String bucketName;

    /** 对象 Key。 */
    private String storageKey;

    /** 访问 URL（服务端生成）。 */
    private String fileUrl;

    /** MIME 类型。 */
    private String mimeType;

    /** 字节数。 */
    private Long sizeBytes;

    /** 宽（本期不自动探测）。 */
    private Integer widthPx;

    /** 高（本期不自动探测）。 */
    private Integer heightPx;

    /** 内容哈希（去重）。 */
    private String sha256Hex;

    /** 状态：0禁用 1可用 2待清理。 */
    private Integer fileStatus;
}