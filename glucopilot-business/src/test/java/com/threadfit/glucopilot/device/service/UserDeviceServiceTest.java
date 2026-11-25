package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.*;
import com.threadfit.glucopilot.device.cloud.DeviceCloudCredentialService;
import com.threadfit.glucopilot.device.controller.UserDeviceController;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.mapper.DeviceSyncLogMapper;
import com.threadfit.glucopilot.device.mapper.DeviceSyncSampleMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import org.mockito.Mockito;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class UserDeviceServiceTest {
    JdbcTemplate jdbc; TransactionTemplate tx; UserDeviceService service; java.sql.Connection schemaConnection;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:user_devices_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String schema;try(var in=getClass().getResourceAsStream("/db/migration/V25__user_device.sql")){schema=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
        int start=schema.indexOf("CREATE TABLE user_device (");schemaConnection=ds.getConnection();
        try(var statement=schemaConnection.createStatement()){
            statement.execute(schema.substring(start,schema.indexOf(';',start)).replace("CONSTRAINT pk_id PRIMARY KEY","PRIMARY KEY"));
            statement.execute("ALTER TABLE user_device ADD COLUMN device_category VARCHAR(32) NULL");
            statement.execute("ALTER TABLE user_device ADD COLUMN device_model VARCHAR(64) NULL");
        }
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(config,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new AuditMetaObjectHandler()));
        config.setEnvironment(new Environment("test",new SpringManagedTransactionFactory(),ds));
        config.addMapper(UserDeviceMapper.class);
        config.addMapper(DeviceSyncLogMapper.class);
        config.addMapper(DeviceSyncSampleMapper.class);
        try(var statement=schemaConnection.createStatement()){
            statement.execute("CREATE TABLE device_sync_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT,device_id BIGINT,provider VARCHAR(32),sample_count INT,created_count INT,remark VARCHAR(255),created_by BIGINT,gmt_create TIMESTAMP(3),updated_by BIGINT,gmt_modified TIMESTAMP(3),deleted_by BIGINT,deleted_at TIMESTAMP(3),is_deleted INT DEFAULT 0)");
            statement.execute("CREATE TABLE device_sync_sample(id BIGINT PRIMARY KEY AUTO_INCREMENT,sync_log_id BIGINT,user_id BIGINT,device_id BIGINT,recorded_at TIMESTAMP(3),node_code VARCHAR(32),glucose_value DECIMAL(4,1),created_by BIGINT,gmt_create TIMESTAMP(3),updated_by BIGINT,gmt_modified TIMESTAMP(3),deleted_by BIGINT,deleted_at TIMESTAMP(3),is_deleted INT DEFAULT 0)");
        }
        var sqlSession=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        DeviceCloudCredentialService cloudCredentials=Mockito.mock(DeviceCloudCredentialService.class);
        Mockito.when(cloudCredentials.linkedDeviceIds(Mockito.anyLong(),Mockito.anyList())).thenReturn(Set.of());
        Mockito.when(cloudCredentials.findActiveByDevice(Mockito.anyLong(),Mockito.anyLong())).thenReturn(null);
        service=new UserDeviceService(sqlSession.getMapper(UserDeviceMapper.class),sqlSession.getMapper(DeviceSyncLogMapper.class),sqlSession.getMapper(DeviceSyncSampleMapper.class),cloudCredentials);
    }
    @AfterEach void cleanup() throws Exception {RequestContext.clear();try{jdbc.execute("DROP ALL OBJECTS");}finally{schemaConnection.close();}}
    DeviceDtos.BindRequest request(String sn){var r=new DeviceDtos.BindRequest();r.setDeviceSn(sn);r.setProvider("HUAWEI");r.setDeviceName("我的设备");return r;}
    @Test void repeatBindingListsOnlyOwnerAndRejectsAnotherUser(){
        var first=tx.execute(s->service.bind(2L,request(" SN-001 ")));
        var repeat=tx.execute(s->service.bind(2L,request("SN-001")));
        assertEquals(first.getId(),repeat.getId());assertEquals(1,service.listMine(2L).size());assertEquals(0,service.listMine(3L).size());
        assertThrows(BizException.class,()->tx.execute(s->service.bind(3L,request("SN-001"))));
        assertEquals(2,jdbc.queryForObject("SELECT user_id FROM user_device",Long.class));
    }
    @Test void unbindActuallyWritesSqlNullAndPreservesDeviceAndSyncHistory(){
        var item=tx.execute(s->service.bind(2L,request("SN-002")));long id=Long.parseLong(item.getId());
        jdbc.update("UPDATE user_device SET last_sync_at='2026-10-20 12:00:00' WHERE id=?",id);
        assertThrows(BizException.class,()->service.unbind(3L,id));
        tx.executeWithoutResult(s->service.unbind(2L,id));
        assertNull(jdbc.queryForObject("SELECT user_id FROM user_device WHERE id=?",Long.class,id));
        assertNotNull(jdbc.queryForObject("SELECT last_sync_at FROM user_device WHERE id=?",java.sql.Timestamp.class,id));
        assertEquals(0,service.listMine(2L).size());
        assertEquals(item.getId(),tx.execute(s->service.bind(3L,request("SN-002"))).getId());
    }
    @Test void stoppedDeviceCannotBeEnabledThroughSelfBinding(){
        var item=tx.execute(s->service.bind(2L,request("SN-003")));
        jdbc.update("UPDATE user_device SET device_status=0 WHERE id=?",Long.parseLong(item.getId()));
        assertThrows(BizException.class,()->tx.execute(s->service.bind(2L,request("SN-003"))));
        assertEquals(0,service.listMine(2L).getFirst().getDeviceStatus());
    }
    @Test void onlyOneAccountCanClaimExistingFreeDeviceConcurrently() throws Exception {
        var item=tx.execute(s->service.bind(1L,request("SN-004")));tx.executeWithoutResult(s->service.unbind(1L,Long.parseLong(item.getId())));
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var tasks=new ArrayList<Future<Boolean>>();for(long user:List.of(2L,3L))tasks.add(pool.submit(()->{gate.await();try{tx.execute(s->service.bind(user,request("SN-004")));return true;}catch(BizException conflict){return false;}}));
            gate.countDown();int successes=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(1,service.listMine(2L).size()+service.listMine(3L).size());
        }finally{pool.shutdownNow();}
    }
    @Test void bindPersistsOptionalCategoryAndModel(){
        var req=request("SN-CAT");req.setDeviceCategory("bgm");req.setDeviceModel(" Accu-Check ");
        var item=tx.execute(s->service.bind(2L,req));
        assertEquals("BGM",item.getDeviceCategory());
        assertEquals("Accu-Check",item.getDeviceModel());
        assertEquals("BGM",jdbc.queryForObject("SELECT device_category FROM user_device WHERE device_sn='SN-CAT'",String.class));
        assertEquals("Accu-Check",jdbc.queryForObject("SELECT device_model FROM user_device WHERE device_sn='SN-CAT'",String.class));
        assertEquals("BGM",service.listMine(2L).getFirst().getDeviceCategory());
    }
    @Test void realHttpBindingValidationAndAnonymousRejection() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new UserDeviceController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(401,mvc.perform(get("/api/v1/devices")).andReturn().getResponse().getStatus());
        RequestContext.setUserId(2L);
        assertEquals(400,mvc.perform(post("/api/v1/devices/bind").contentType("application/json").content("{\"deviceSn\":\"\",\"provider\":\"HUAWEI\"}")).andReturn().getResponse().getStatus());
        var response=mvc.perform(post("/api/v1/devices/bind").contentType("application/json").content("{\"deviceSn\":\"HTTP-SN\",\"provider\":\"HUAWEI\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("\"code\":0"));
        assertEquals(200,mvc.perform(get("/api/v1/devices")).andReturn().getResponse().getStatus());
    }

    @Test void listSyncSamplesReturnsOwnedSnapshotAndRejectsOthers() {
        var item = tx.execute(s -> service.bind(2L, request("SN-SAMPLE")));
        long deviceId = Long.parseLong(item.getId());
        jdbc.update(
                "INSERT INTO device_sync_log(id,user_id,device_id,provider,sample_count,created_count,remark,gmt_create,is_deleted) VALUES (11,2,?, 'MOCK',2,2,'demo','2026-11-24 10:00:00',0)",
                deviceId);
        jdbc.update(
                "INSERT INTO device_sync_sample(sync_log_id,user_id,device_id,recorded_at,node_code,glucose_value,is_deleted) VALUES (11,2,?, '2026-11-24 08:00:00','before_breakfast',5.6,0)",
                deviceId);
        jdbc.update(
                "INSERT INTO device_sync_sample(sync_log_id,user_id,device_id,recorded_at,node_code,glucose_value,is_deleted) VALUES (11,2,?, '2026-11-24 09:00:00','after_breakfast',7.2,0)",
                deviceId);

        DeviceDtos.SyncSamplesResponse mine = service.listSyncSamples(2L, deviceId, 11L);
        assertEquals(2, mine.getSampleCount());
        assertEquals(2, mine.getPoints().size());
        assertEquals("5.6", mine.getPoints().getFirst().getGlucoseValue().toPlainString());
        assertThrows(BizException.class, () -> service.listSyncSamples(3L, deviceId, 11L));
        assertThrows(BizException.class, () -> service.listSyncSamples(2L, deviceId, 99L));
    }
}
