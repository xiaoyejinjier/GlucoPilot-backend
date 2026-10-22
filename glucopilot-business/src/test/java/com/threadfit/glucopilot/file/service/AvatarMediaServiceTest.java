package com.threadfit.glucopilot.file.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.file.controller.AvatarMediaController;
import com.threadfit.glucopilot.file.dto.FileDtos;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AvatarMediaServiceTest {
    JdbcTemplate jdbc;ObjectStorageService storage;AvatarMediaService service;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:avatar_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");
        // Keep the database across independent JdbcTemplate calls during this test.
        ds.setUrl(ds.getUrl()+";DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,account_status INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE media_file(id BIGINT PRIMARY KEY,user_id BIGINT,biz_type INT,file_status INT,is_deleted INT,storage_key VARCHAR(100),mime_type VARCHAR(50),storage_provider INT,bucket_name VARCHAR(50))");
        jdbc.update("INSERT INTO user_account VALUES(1,1,0)");
        jdbc.update("INSERT INTO media_file VALUES(50,1,1,1,0,'1/2026/10/1/avatar.png','image/png',1,'team-bucket')");
        storage=mock(ObjectStorageService.class);when(storage.provider()).thenReturn(StorageProvider.MINIO);when(storage.bucket()).thenReturn("team-bucket");
        service=new AvatarMediaService(jdbc,storage);
    }
    @AfterEach void cleanup(){jdbc.execute("DROP ALL OBJECTS");RequestContext.clear();}
    @Test void privateBucketAvatarIsReadByBackendAndReturnedWithStableBinaryHeaders()throws Exception{
        when(storage.objectExists(anyString())).thenReturn(true);when(storage.getObject(anyString())).thenReturn(new byte[]{1,2,3});
        var response=MockMvcBuilders.standaloneSetup(new AvatarMediaController(service)).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/api/v1/media-files/avatars/50")).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("image/png",response.getContentType());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
        assertArrayEquals(new byte[]{1,2,3},response.getContentAsByteArray());verify(storage).getObject("1/2026/10/1/avatar.png");verify(storage,never()).resolvePublicUrl(anyString());
    }
    @Test void otherBusinessTypesNeverBecomePublicAvatars(){
        for(int type:new int[]{2,3,4,5,6,7,8}){jdbc.update("UPDATE media_file SET biz_type=?",type);assertThrows(BizException.class,()->service.read(50));}
        verify(storage,never()).getObject(anyString());verify(storage,never()).objectExists(anyString());
    }
    @Test void everySupportedAvatarUploadFormatCanBeRead(){
        when(storage.objectExists(anyString())).thenReturn(true);when(storage.getObject(anyString())).thenReturn(new byte[]{1});
        for(String mime:new String[]{"image/jpeg","image/png","image/webp","image/gif"}){
            jdbc.update("UPDATE media_file SET mime_type=?",mime);assertEquals(mime,service.read(50).mime());
        }
    }
    @Test void deletedOrDisabledMediaAndOwnersAreNotVisible(){
        for(String sql:new String[]{"UPDATE media_file SET file_status=0","UPDATE media_file SET file_status=1,is_deleted=1","UPDATE media_file SET is_deleted=0; UPDATE user_account SET account_status=0","UPDATE user_account SET account_status=1,is_deleted=1"}){
            jdbc.execute(sql);assertThrows(BizException.class,()->service.read(50));
        }
        verify(storage,never()).getObject(anyString());
    }
    @Test void mismatchedStorageOrUnknownObjectIsNotServed(){
        jdbc.update("UPDATE media_file SET bucket_name='different'");assertThrows(BizException.class,()->service.read(50));
        jdbc.update("UPDATE media_file SET bucket_name='team-bucket',storage_provider=2");assertThrows(BizException.class,()->service.read(50));
        jdbc.update("UPDATE media_file SET storage_provider=1");when(storage.objectExists(anyString())).thenReturn(false);assertThrows(BizException.class,()->service.read(50));verify(storage,never()).getObject(anyString());
    }
    @Test void absentAndUnsafeImageTypesReturn404NotStorageDetails()throws Exception{
        jdbc.update("UPDATE media_file SET mime_type='image/svg+xml'");
        var mvc=MockMvcBuilders.standaloneSetup(new AvatarMediaController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(404,mvc.perform(get("/api/v1/media-files/avatars/50")).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/v1/media-files/avatars/99")).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/api/v1/media-files/avatars/-1")).andReturn().getResponse().getStatus());
    }
    @Test void stableReferencesAcceptOnlyAvatarPathsAndIds(){
        assertEquals(50L,AvatarMediaService.referenceId("/api/v1/media-files/avatars/50"));
        assertEquals(50L,AvatarMediaService.referenceId("https://old.example.test/api/v1/media-files/avatars/50"));
        for(String bad:new String[]{"/api/v1/media-files/avatars/0","/api/v1/media-files/avatars/9999999999999999999","/api/v1/media-files/avatars/50?key=other","/api/v1/community/article-images/50","file:/api/v1/media-files/avatars/50","data:image/png;base64,example","wxfile://example.jpg"})assertNull(AvatarMediaService.referenceId(bad),bad);
    }
    @Test void avatarRegistrationReturnsStableReferenceWhilePreservingStorageMetadata(){
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),MediaFile.class);
        var mapper=mock(MediaFileMapper.class);doAnswer(invocation->{((MediaFile)invocation.getArgument(0)).setId(50L);return 1;}).when(mapper).insert(any(MediaFile.class));
        when(storage.objectExists(anyString())).thenReturn(true);when(storage.resolvePublicUrl(anyString())).thenReturn("http://private-bucket.example.test/raw-avatar");
        var media=new MediaFileServiceImpl(mapper,storage,null,null);
        var request=new FileDtos.RegisterRequest();request.setBizType(1);request.setMimeType("image/png");request.setSizeBytes(3L);request.setStorageKey("1/2026/10/1/avatar.png");
        var result=media.register(request,1L);assertEquals("/api/v1/media-files/avatars/50",result.getFileUrl());
        var row=org.mockito.ArgumentCaptor.forClass(MediaFile.class);verify(mapper).insert(row.capture());assertEquals("team-bucket",row.getValue().getBucketName());assertEquals("http://private-bucket.example.test/raw-avatar",row.getValue().getFileUrl());
    }
}
