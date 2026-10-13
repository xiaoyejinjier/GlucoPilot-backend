package com.threadfit.glucopilot.file.storage;

import com.aliyun.oss.OSS;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.config.OssClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisabledObjectStorageServiceTest {

    @Test
    void startsWithoutStorageCredentialsAndRejectsAllStorageOperations() {
        new ApplicationContextRunner()
                .withPropertyValues("glucopilot.storage.provider=disabled")
                .withUserConfiguration(DisabledObjectStorageService.class, OssClientConfig.class,
                        AliyunOssObjectStorageService.class, MinioObjectStorageService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ObjectStorageService.class);
                    assertThat(context).doesNotHaveBean(OSS.class);
                    assertThat(context).doesNotHaveBean(AliyunOssObjectStorageService.class);
                    assertThat(context).doesNotHaveBean(MinioObjectStorageService.class);
                    ObjectStorageService service = context.getBean(ObjectStorageService.class);
                    assertRejected(() -> service.createUploadCredential(MediaBizType.AVATAR, "png", 1L, 1L));
                    assertRejected(() -> service.resolvePublicUrl("object"));
                    assertRejected(() -> service.objectExists("object"));
                    assertRejected(() -> service.getObject("object"));
                    assertRejected(() -> service.provider());
                    assertRejected(() -> service.bucket());
                });
    }

    @Test
    void disabledImplementationIsNotSelectedForEnabledProviders() {
        for (String provider : new String[] {"aliyun-oss", "minio"}) {
            new ApplicationContextRunner()
                    .withPropertyValues("glucopilot.storage.provider=" + provider)
                    .withUserConfiguration(DisabledObjectStorageService.class)
                    .run(context -> assertThat(context).doesNotHaveBean(DisabledObjectStorageService.class));
        }
    }

    private void assertRejected(Runnable call) {
        BizException exception = assertThrows(BizException.class, call::run);
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BIZ_REJECTED);
        assertThat(exception.getMessage()).contains("文件存储未启用");
    }
}
