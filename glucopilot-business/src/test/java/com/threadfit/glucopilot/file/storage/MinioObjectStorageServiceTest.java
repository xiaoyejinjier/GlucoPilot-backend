package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MinIO 集成测试（dev mock，完整入库流程不跳过）。
 *
 * 前置条件：本地 MinIO 运行在 {@code http://localhost:9000}（admin/admin123456），
 * 桶 {@code glucopilot-dev}。未运行时测试自动跳过，不阻塞本地单元测试 / CI。
 * <p>上传走同源代理的等价路径 {@code acceptUpload}（HTTP 代理层的契约由
 * {@code MinioFailureSemanticsTest} 覆盖），本类覆盖的是 MinIO 真实语义：
 * 写入后对象可 HEAD、可 Range 读；缺失对象与连通/权限故障区分。
 */
class MinioObjectStorageServiceTest {

    private static final String ENDPOINT = "http://localhost:9000";
    private static final String BUCKET = "glucopilot-dev";
    private static final Long USER_ID = 1001L;

    private static MinioObjectStorageService storageService;

    @BeforeAll
    static void setUp() {
        StorageProperties props = new StorageProperties();
        props.setProvider("minio");
        props.setEndpoint(ENDPOINT);
        props.setAccessKey("admin");
        props.setSecretKey("admin123456");
        props.setBucket(BUCKET);

        MinioClient client = MinioClient.builder()
                .endpoint(ENDPOINT)
                .credentials("admin", "admin123456")
                .build();

        boolean reachable;
        try {
            client.listBuckets();
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "MinIO 未运行于 " + ENDPOINT + "，跳过集成测试");

        // 桶不存在则创建（部署时已建，这里兜底保证测试自洽）
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MinIO 桶失败", e);
        }

        storageService = new MinioObjectStorageService(props);
    }

    /** 现行 createUploadCredential 基于 ServletUriComponentsBuilder 生成同源代理 URL，需要请求上下文。 */
    @BeforeEach
    void bindRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("backend.example.test");
        request.setServerPort(8080);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterAll
    static void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void credentialExposesSameOriginProxyUrlAndContractConformingKey() {
        UploadCredential credential =
                storageService.createUploadCredential(MediaBizType.AVATAR, "jpg", 1024L, USER_ID);

        // 同源代理上传入口（不走 MinIO 预签名直传，避免依赖桶 CORS）
        assertTrue(credential.getUploadUrl().startsWith(
                "http://backend.example.test:8080/api/v1/media-files/minio-uploads/"),
                "uploadUrl 应为同源代理地址: " + credential.getUploadUrl());
        String token = credential.getUploadUrl().substring(credential.getUploadUrl().lastIndexOf('/') + 1);
        assertTrue(token.matches("[0-9a-f]{32}"), "一次性 token 应为 32 位 hex: " + token);
        assertEquals((long) ObjectStorageService.EXPIRE_SECONDS, credential.getExpireIn());

        // storageKey = {bizType}/{yyyy}/{MM}/{userId}/{uuid32}.{ext}
        assertTrue(credential.getStorageKey().matches(
                        MediaBizType.AVATAR.getCode() + "/\\d{4}/\\d{2}/" + USER_ID + "/[0-9a-f]{32}\\.jpg"),
                "storageKey 格式不符: " + credential.getStorageKey());
    }

    @Test
    void uploadedObjectCanBeStattedAndRangeRead() {
        UploadCredential credential =
                storageService.createUploadCredential(MediaBizType.GLUCOSE, "png", 2048L, USER_ID);
        String token = credential.getUploadUrl().substring(credential.getUploadUrl().lastIndexOf('/') + 1);
        byte[] body = new byte[2048];
        new java.util.Random(42).nextBytes(body);

        storageService.acceptUpload(token, new ByteArrayInputStream(body), body.length);

        assertTrue(storageService.objectExists(credential.getStorageKey()), "写入后对象应存在");
        assertEquals(body.length, storageService.objectSize(credential.getStorageKey()), "HEAD 查询应返回真实大小");
        try (InputStream range = storageService.openObject(credential.getStorageKey(), 100, 28)) {
            assertArrayEquals(java.util.Arrays.copyOfRange(body, 100, 128),
                    range.readAllBytes(), "Range 读取应返回请求区间");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }

        // 一次性凭证：同一 token 二次消费必须拒绝
        assertThrows(IllegalArgumentException.class,
                () -> storageService.acceptUpload(token, new ByteArrayInputStream(body), body.length));
    }

    @Test
    void unknownKeyDoesNotExist() {
        assertFalse(storageService.objectExists("1/2099/01/" + USER_ID + "/not-exist.png"));
    }

    @Test
    void missingKeySizeAndRangeReadAreNotFound() {
        String key = "1/2099/01/" + USER_ID + "/not-exist.png";
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(BizException.class, () -> storageService.objectSize(key)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(BizException.class, () -> storageService.openObject(key, 0, 1)).getErrorCode());
    }

    @Test
    void resolvePublicUrlUsesEndpointAndBucket() {
        String key = "1/2026/09/" + USER_ID + "/abc123.png";
        assertEquals(ENDPOINT + "/" + BUCKET + "/" + key, storageService.resolvePublicUrl(key));
    }

    @Test
    void providerIsMinio() {
        assertEquals(StorageProvider.MINIO, storageService.provider());
    }
}
