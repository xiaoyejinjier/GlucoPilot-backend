package com.threadfit.glucopilot.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置（prefix=glucopilot.storage）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "glucopilot.storage")
public class StorageProperties {

    /** local / minio / aliyun-oss。 */
    private String provider;

    /** 服务端点（dev=MinIO 地址；prod=OSS Endpoint）。 */
    private String endpoint;

    private String accessKey;

    private String secretKey;

    private String bucket;

    /** OSS 地域（仅 prod 使用，dev 忽略）。 */
    private String region;

    /** 本地开发存储目录；仅 provider=local 时使用。 */
    private String localDir = "./data/uploads";
}
