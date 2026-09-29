package com.threadfit.glucopilot.file.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 客户端装配（仅 provider=aliyun-oss 时生效）。
 */
@Configuration
@ConditionalOnProperty(prefix = "glucopilot.storage", name = "provider", havingValue = "aliyun-oss")
public class OssClientConfig {

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(StorageProperties props) {
        return new OSSClientBuilder().build(props.getEndpoint(), props.getAccessKey(), props.getSecretKey());
    }
}