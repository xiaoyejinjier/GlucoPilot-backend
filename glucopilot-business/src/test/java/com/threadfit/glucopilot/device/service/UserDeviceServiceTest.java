package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.*;
import com.threadfit.glucopilot.device.controller.UserDeviceController;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.mapper.DeviceSyncLogMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
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
        try(var statement=schemaConnection.createStatement()){statement.execute(schema.substring(start,schema.indexOf(';',start)).replace("CONSTRAINT pk_id PRIMARY KEY","PRIMARY KEY"));}
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(config,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new AuditMetaObjectHandler()));
        config.setEnvironment(new Environment("test",new SpringManagedTransactionFactory(),ds));
        config.addMapper(UserDeviceMapper.class);
        config.addMapper(DeviceSyncLogMapper.class);
        var sqlSession=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        service=new UserDeviceService(sqlSession.getMapper(UserDeviceMapper.class),sqlSession.getMapper(DeviceSyncLogMapper.class));
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
    @Test void realHttpBindingValidationAndAnonymousRejection() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new UserDeviceController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(401,mvc.perform(get("/api/v1/devices")).andReturn().getResponse().getStatus());
        RequestContext.setUserId(2L);
        assertEquals(400,mvc.perform(post("/api/v1/devices/bind").contentType("application/json").content("{\"deviceSn\":\"\",\"provider\":\"HUAWEI\"}")).andReturn().getResponse().getStatus());
        var response=mvc.perform(post("/api/v1/devices/bind").contentType("application/json").content("{\"deviceSn\":\"HTTP-SN\",\"provider\":\"HUAWEI\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("\"code\":0"));
        assertEquals(200,mvc.perform(get("/api/v1/devices")).andReturn().getResponse().getStatus());
    }
}
