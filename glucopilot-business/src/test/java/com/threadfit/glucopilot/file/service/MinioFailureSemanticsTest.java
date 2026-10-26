package com.threadfit.glucopilot.file.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.config.StorageProperties;
import com.threadfit.glucopilot.file.storage.MinioObjectStorageService;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MinioFailureSemanticsTest {
    MinioClient client;MinioObjectStorageService storage;
    @BeforeEach void setup(){
        var props=new StorageProperties();props.setEndpoint("http://unused.example.test:9000");props.setAccessKey("test-access");props.setSecretKey("test-secret-value");props.setBucket("test-bucket");
        storage=new MinioObjectStorageService(props);client=mock(MinioClient.class);ReflectionTestUtils.setField(storage,"minioClient",client);
    }
    private ErrorResponseException response(String code){
        var exception=mock(ErrorResponseException.class);var error=mock(ErrorResponse.class);when(exception.errorResponse()).thenReturn(error);when(error.code()).thenReturn(code);return exception;
    }
    @Test void onlyMissingObjectsAreNotFoundWhileNetworkAndPermissionFailuresStayServiceErrors()throws Exception{
        var missing=response("NoSuchKey");when(client.statObject(any(StatObjectArgs.class))).thenThrow(missing);assertFalse(storage.objectExists("key"));
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(new IOException("sensitive upstream detail"));
        var network=assertThrows(BizException.class,()->storage.objectExists("key"));assertEquals(ErrorCode.INTERNAL_ERROR,network.getErrorCode());assertFalse(network.getMessage().contains("sensitive"));
        var denied=response("AccessDenied");when(client.statObject(any(StatObjectArgs.class))).thenThrow(denied);assertEquals(ErrorCode.INTERNAL_ERROR,assertThrows(BizException.class,()->storage.objectExists("key")).getErrorCode());
    }
    @Test void readingMissingObjectIs404AndDisconnectedStorageIsNotReportedAsMissing()throws Exception{
        var missing=response("NoSuchKey");when(client.getObject(any(GetObjectArgs.class))).thenThrow(missing);assertEquals(ErrorCode.NOT_FOUND,assertThrows(BizException.class,()->storage.getObject("key")).getErrorCode());
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(new IOException("signed-request-details"));
        var error=assertThrows(BizException.class,()->storage.getObject("key"));assertEquals(ErrorCode.INTERNAL_ERROR,error.getErrorCode());assertEquals("文件存储暂时不可用，请稍后重试",error.getMessage());
    }
    @Test void sameOriginCredentialUploadsOnceAndKeepsPrivateObjectKey()throws Exception{
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setScheme("https");request.setServerName("backend.example.test");request.setServerPort(443);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try{
            var credential=storage.createUploadCredential(com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType.AVATAR,"jpg",3L,2L);
            assertTrue(credential.getUploadUrl().startsWith("https://backend.example.test/api/v1/media-files/minio-uploads/"));
            String token=credential.getUploadUrl().substring(credential.getUploadUrl().lastIndexOf('/')+1);
            storage.acceptUpload(token,new byte[]{1,2,3});verify(client).putObject(any(PutObjectArgs.class));
            assertThrows(IllegalArgumentException.class,()->storage.acceptUpload(token,new byte[]{1,2,3}));
        }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
    }
}
