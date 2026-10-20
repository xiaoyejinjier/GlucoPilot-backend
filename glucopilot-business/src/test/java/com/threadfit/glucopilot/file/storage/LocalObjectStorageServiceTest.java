package com.threadfit.glucopilot.file.storage;

import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalObjectStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalObjectStorageService service;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setProvider("local");
        properties.setLocalDir(tempDir.toString());
        service = new LocalObjectStorageService(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("dev-host.invalid");
        request.setServerPort(8080);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void uploadCredentialStoresAndReadsObjectWithoutExternalService() {
        byte[] content = new byte[]{1, 2, 3, 4};
        UploadCredential credential = service.createUploadCredential(
                MediaBizType.GLUCOSE, "jpg", content.length, 7L);

        assertTrue(credential.getUploadUrl().startsWith(
                "http://dev-host.invalid:8080/api/v1/media-files/local-uploads/"));
        String token = credential.getUploadUrl().substring(credential.getUploadUrl().lastIndexOf('/') + 1);
        service.acceptUpload(token, content);

        assertTrue(service.objectExists(credential.getStorageKey()));
        assertArrayEquals(content, service.getObject(credential.getStorageKey()));
        assertTrue(service.resolvePublicUrl(credential.getStorageKey())
                .startsWith("http://dev-host.invalid:8080/api/v1/media-files/local-objects?key="));
        assertThrows(IllegalArgumentException.class, () -> service.acceptUpload(token, content));
    }

    @Test
    void rejectsContentWhoseSizeDoesNotMatchCredential() {
        UploadCredential credential = service.createUploadCredential(
                MediaBizType.MEAL, "png", 10, 8L);
        String token = credential.getUploadUrl().substring(credential.getUploadUrl().lastIndexOf('/') + 1);

        assertThrows(IllegalArgumentException.class,
                () -> service.acceptUpload(token, new byte[]{1, 2}));
    }
    @Test void articleImagesCannotBypassVisibilityThroughLocalPublicPreviewOrPathAliases() {
        byte[] bytes={1,2,3,4};
        var credential=service.createUploadCredential(MediaBizType.ARTICLE_IMAGE,"png",4,8L);
        service.acceptUpload(credential.getUploadUrl().substring(credential.getUploadUrl().lastIndexOf('/')+1),bytes);
        assertArrayEquals(bytes,service.getObject(credential.getStorageKey()));
        assertThrows(com.threadfit.glucopilot.common.exception.BizException.class,()->service.getPublicObject(credential.getStorageKey()));
        assertThrows(com.threadfit.glucopilot.common.exception.BizException.class,()->service.getPublicObject("3/../"+credential.getStorageKey()));
    }
}
