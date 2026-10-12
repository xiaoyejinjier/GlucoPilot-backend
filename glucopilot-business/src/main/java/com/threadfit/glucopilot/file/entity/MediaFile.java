package com.threadfit.glucopilot.file.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 媒体文件元数据实体（映射表 media_file，见 V1 L398-434）。
 * <p>记录的是「一次媒体资源」的元数据：上传者（C 端 userId / 运营 opsId 二选一）、
 * 业务归属（bizType + bizId）、对象存储在服务端的定位（storageProvider / bucketName / storageKey）、
 * 供前端展示的 fileUrl，以及用于内容去重的 sha256Hex。
 * <p>storageKey 规范为 {@code {bizType}/{yyyy}/{MM}/{userId}/{uuid}.{ext}}（由业务侧统一生成）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("media_file")
public class MediaFile extends BaseEntity {

    /** C 端上传用户，可空（运营上传场景为空）。 */
    private Long userId;

    /** 运营上传，可空（C 端上传场景为空）。 */
    private Long opsId;

    /** 业务类型（CodeEnum.MediaBizType，1-6）。 */
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