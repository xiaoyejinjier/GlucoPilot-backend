package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MinIO 集成测试（dev mock，完整入库流程不跳过）。
 *
 * <p>前置条件：本地 MinIO 运行在 {@code http://localhost:9000}（minioadmin/minioadmin），
 * 桶 {@code glucopilot-dev}。未运行时测试自动跳过，不阻塞本地单元测试 / CI。
 */
class MinioObjectStorageServiceTest {

    private static final String ENDPOINT = "http://localhost:9000";
    private static final String BUCKET = "glucopilot-dev";
    private static final Long USER_ID = 1001L;

    private static MinioObjectStorageService storageService;
    private static HttpClient httpClient;

    @BeforeAll
    static void setUp() {
        StorageProperties props = new StorageProperties();
        props.setProvider("minio");
        props.setEndpoint(ENDPOINT);
        props.setAccessKey("minioadmin");
        props.setSecretKey("minioadmin");
        props.setBucket(BUCKET);

        MinioClient client = MinioClient.builder()
                .endpoint(ENDPOINT)
                .credentials("minioadmin", "minioadmin")
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
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void credentialHasPresignedUrlAndContractConformingKey() {
        UploadCredential credential =
                storageService.createUploadCredential(MediaBizType.AVATAR, "jpg", 1024L, USER_ID);

        assertNotNull(credential.getUploadUrl());
        assertTrue(credential.getUploadUrl().contains("/" + BUCKET + "/"), "预签名 URL 应为 path-style");
        assertTrue(credential.getUploadUrl().contains("X-Amz-Signature"), "预签名 URL 应含签名");
        assertEquals((long) ObjectStorageService.EXPIRE_SECONDS, credential.getExpireIn());

        // storageKey = {bizType}/{yyyy}/{MM}/{userId}/{uuid32}.{ext}
        assertTrue(credential.getStorageKey().matches(
                        MediaBizType.AVATAR.getCode() + "/\\d{4}/\\d{2}/" + USER_ID + "/[0-9a-f]{32}\\.jpg"),
                "storageKey 格式不符: " + credential.getStorageKey());
    }

    @Test
    void uploadedObjectCanBeStatted() throws Exception {
        UploadCredential credential =
                storageService.createUploadCredential(MediaBizType.GLUCOSE, "png", 2048L, USER_ID);

        byte[] body = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(credential.getUploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "image/png")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, "直传应成功，实际 " + response.statusCode() + ": " + response.body());

        assertTrue(storageService.objectExists(credential.getStorageKey()), "对象直传后应存在");
    }

    @Test
    void unknownKeyDoesNotExist() {
        assertFalse(storageService.objectExists("1/2099/01/" + USER_ID + "/not-exist.png"));
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