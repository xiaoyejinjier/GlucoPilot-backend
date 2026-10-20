package com.threadfit.glucopilot.export;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DataExportServiceTest {
    private JdbcTemplate jdbc;private TransactionTemplate tx;private DataExportService service;private java.sql.Connection connection;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:exports_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));connection=ds.getConnection();
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,username VARCHAR(64),account_status INT DEFAULT 1,is_deleted INT DEFAULT 0)");jdbc.execute("CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname VARCHAR(64),is_deleted INT DEFAULT 0)");
        String schema=new ClassPathResource("db/migration/V1__init_schema.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String table:List.of("data_export_task","glucose_record","meal_record")){int start=schema.indexOf("CREATE TABLE "+table+" (");String ddl=schema.substring(start,schema.indexOf(';',start)).replace("CONSTRAINT pk_id PRIMARY KEY","PRIMARY KEY").replace("uk_user_date_node","uk_"+table+"_date_node").replace("idx_user_date","idx_"+table+"_date");try(var s=connection.createStatement()){s.execute(ddl);}}
        var migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V34__drafts_notifications_exports.sql"));migration.setSqlScriptEncoding("UTF-8");migration.populate(connection);
        jdbc.update("INSERT INTO user_account(id,username) VALUES(1,'one'),(2,'two')");jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(1,'用户甲'),(2,'用户乙')");
        service=new DataExportService(jdbc);
    }
    @AfterEach void cleanup() throws Exception {try{jdbc.execute("DROP ALL OBJECTS");}finally{connection.close();}}
    private DataExportService.Task create(long user,String key){return tx.execute(s->service.create(user,new DataExportService.Create(LocalDate.now().minusDays(6),LocalDate.now(),key)));}
    @Test void exportContainsOnlyOwnedRealRecordsAndDownloadsImmutableActualXlsx() throws Exception {
        jdbc.update("INSERT INTO glucose_record(user_id,record_date,node_code,glucose_value) VALUES(1,CURRENT_DATE,'before_breakfast',6.2),(2,CURRENT_DATE,'before_breakfast',9.1)");
        jdbc.update("INSERT INTO glucose_record(user_id,record_date,node_code,glucose_value,is_deleted,deleted_at) VALUES(1,CURRENT_DATE,'before_sleep',7.1,1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO meal_record(user_id,record_date,node_code,foods_text,carbs_g) VALUES(1,CURRENT_DATE,'lunch','早餐记录',26.0),(2,CURRENT_DATE,'lunch','乙的记录',55.0)");
        var task=create(1,"export-real-001");long id=Long.parseLong(task.id());assertEquals(task.id(),create(1,"export-real-001").id());
        var done=tx.execute(s->service.generate(1L,id));assertEquals("done",done.status());assertEquals(1,done.glucoseCount());assertEquals(1,done.mealCount());
        byte[] bytes=service.file(1L,id);assertArrayEquals(bytes,service.file(null,id));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){assertEquals(2,book.getNumberOfSheets());assertEquals(1,book.getSheet("血糖记录").getLastRowNum());assertEquals(6.2,book.getSheet("血糖记录").getRow(1).getCell(2).getNumericCellValue());assertEquals("早餐记录",book.getSheet("饮食记录").getRow(1).getCell(2).getStringCellValue());}
        assertThrows(BizException.class,()->service.file(2L,id));assertThrows(BizException.class,()->tx.execute(s->service.generate(2L,id)));
        jdbc.update("UPDATE glucose_record SET glucose_value=8.1 WHERE user_id=1 AND is_deleted=0");tx.execute(s->service.generate(1L,id));assertArrayEquals(bytes,service.file(1L,id));
        assertEquals(1,service.list(1L,null,null,new PageQuery()).getTotal());assertEquals(0,service.list(2L,null,null,new PageQuery()).getTotal());assertEquals(1,service.list(null,"done","用户甲",new PageQuery()).getTotal());
    }
    @Test void emptyDataProducesEmptyHeadersAndInvalidRangesOrChangedIdempotencyAreRejected() throws Exception {
        var task=create(1,"export-empty-01");long id=Long.parseLong(task.id());tx.execute(s->service.generate(1L,id));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.file(1L,id)))){assertEquals(0,book.getSheetAt(0).getLastRowNum());assertEquals(0,book.getSheetAt(1).getLastRowNum());assertEquals("日期",book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());}
        assertThrows(BizException.class,()->tx.execute(s->service.create(1,new DataExportService.Create(LocalDate.now(),LocalDate.now().plusDays(1),"export-future-1"))));
        assertThrows(BizException.class,()->tx.execute(s->service.create(1,new DataExportService.Create(LocalDate.now().minusYears(2),LocalDate.now(),"export-too-long"))));
        assertThrows(BizException.class,()->tx.execute(s->service.create(1,new DataExportService.Create(LocalDate.now(),LocalDate.now(),"export-empty-01"))));
    }
    @Test void failedGenerationIsRecordedAndNeverProvidesFakeDownload() {
        var task=create(1,"export-failure-1");long id=Long.parseLong(task.id());jdbc.execute("DROP TABLE meal_record");var failed=tx.execute(s->service.generate(1L,id));assertEquals("failed",failed.status());assertEquals("文件生成失败，请稍后重试",failed.error());assertThrows(BizException.class,()->service.file(1L,id));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM data_export_file",Integer.class));
    }
}
