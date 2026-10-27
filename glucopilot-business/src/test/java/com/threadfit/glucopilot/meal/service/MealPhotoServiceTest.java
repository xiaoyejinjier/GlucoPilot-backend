package com.threadfit.glucopilot.meal.service;

import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealPhotoServiceTest {
    private JdbcTemplate jdbc;
    private ObjectStorageService storage;
    private MealPhotoService service;
    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:meal_photo_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE user_account(id BIGINT,account_status INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE meal_record(user_id BIGINT,media_id BIGINT,is_deleted INT)");
        jdbc.execute("CREATE TABLE media_file(id BIGINT,user_id BIGINT,ops_id BIGINT,biz_type INT,is_deleted INT,file_status INT,storage_key VARCHAR(100),mime_type VARCHAR(40),storage_provider INT,bucket_name VARCHAR(40),size_bytes BIGINT)");
        jdbc.update("INSERT INTO user_account VALUES(1,1,0),(2,1,0)");
        jdbc.update("INSERT INTO meal_record VALUES(1,88,0)");
        jdbc.update("INSERT INTO media_file VALUES(88,1,NULL,3,0,1,'meal.jpg','image/jpeg',1,'test-bucket',3)");
        storage = mock(ObjectStorageService.class);
        service = new MealPhotoService(jdbc,storage);
    }
    private void validStorage() {
        when(storage.provider()).thenReturn(StorageProvider.MINIO);
        when(storage.bucket()).thenReturn("test-bucket");
    }
    @Test void ownSavedMealReturnsOriginalImageAndStablePath() {
        validStorage();when(storage.getObject("meal.jpg")).thenReturn(new byte[]{1,2,3});
        var image=service.read(1,88);
        assertArrayEquals(new byte[]{1,2,3},image.bytes());assertEquals("image/jpeg",image.mime());
        assertEquals("/api/v1/meal-records/images/88",MealPhotoService.path(88));
    }
    @Test void anotherAccountCannotReadEvenIfItReferencesTheSameMedia() {
        jdbc.update("INSERT INTO meal_record VALUES(2,88,0)");
        assertThrows(BizException.class,()->service.read(2,88));verifyNoInteractions(storage);
    }
    @Test void deletedMealMediaAndDisabledAccountCannotRead() {
        jdbc.update("UPDATE meal_record SET is_deleted=1");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("UPDATE meal_record SET is_deleted=0");jdbc.update("UPDATE media_file SET is_deleted=1");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("UPDATE media_file SET is_deleted=0");jdbc.update("UPDATE user_account SET account_status=0");assertThrows(BizException.class,()->service.read(1,88));
        verifyNoInteractions(storage);
    }
    @Test void unregisteredOrWrongBusinessAndOpsPhotosCannotRead() {
        jdbc.update("DELETE FROM meal_record");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("INSERT INTO meal_record VALUES(1,88,0)");
        jdbc.update("UPDATE media_file SET file_status=0");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("UPDATE media_file SET file_status=1,biz_type=1");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("UPDATE media_file SET biz_type=3,ops_id=9");assertThrows(BizException.class,()->service.read(1,88));
        verifyNoInteractions(storage);
    }
    @Test void storageIdentityMimeAndInvalidSizeAreRejectedBeforeDownload() {
        validStorage();jdbc.update("UPDATE media_file SET bucket_name='wrong'");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("UPDATE media_file SET bucket_name='test-bucket',size_bytes=0");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("UPDATE media_file SET size_bytes=20971521");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("UPDATE media_file SET size_bytes=3,storage_provider=2");assertThrows(BizException.class,()->service.read(1,88));
        jdbc.update("UPDATE media_file SET storage_provider=1,mime_type='text/html'");assertThrows(BizException.class,()->service.read(1,88));
        verify(storage,never()).getObject(any());
    }
    @Test void webpWorksAndMissingObjectIsNotReturnedAsEmptyPhoto() {
        validStorage();jdbc.update("UPDATE media_file SET mime_type='image/webp'");
        when(storage.getObject("meal.jpg")).thenReturn(new byte[]{1,2,3},new byte[0]);
        assertEquals("image/webp",service.read(1,88).mime());
        assertThrows(BizException.class,()->service.read(1,88));
    }
}
