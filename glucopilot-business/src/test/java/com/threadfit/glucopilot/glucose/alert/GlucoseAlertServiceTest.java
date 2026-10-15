package com.threadfit.glucopilot.glucose.alert;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 直接执行迁移、查询与事务；不使用共享数据库，也不替换真实 Repository。 */
class GlucoseAlertServiceTest {
    private JdbcTemplate jdbc;
    private GlucoseAlertRepository repository;
    private GlucoseAlertService service;
    private TransactionTemplate tx;
    private java.sql.Connection migrationConnection;
    private final LocalDate day=LocalDate.of(2026,10,15);
    private final LocalDateTime at=day.atTime(8,0);

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:alerts_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,username VARCHAR(64),phone VARCHAR(20),is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname VARCHAR(64),is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE glucose_record(id BIGINT PRIMARY KEY,user_id BIGINT,record_date DATE,node_code VARCHAR(32),glucose_value DECIMAL(4,1),source_type INT DEFAULT 1,remark VARCHAR(256),recorded_at TIMESTAMP(3),is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT AUTO_INCREMENT PRIMARY KEY,menu_name VARCHAR(128),parent_id BIGINT,order_num INT,path VARCHAR(256),component VARCHAR(256),menu_type CHAR(1),visible CHAR(1),status CHAR(1),perms VARCHAR(128),icon VARCHAR(100),create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(64),del_flag CHAR(1) DEFAULT '0')");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
        jdbc.update("INSERT INTO sys_role(role_id,role_key) VALUES(1,'admin'),(2,'viewer')");
        jdbc.update("INSERT INTO sys_menu(menu_id,perms) VALUES(100,'health:archive:view')");
        jdbc.update("INSERT INTO sys_role_menu VALUES(2,100)");
        var migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V16__glucose_alert_center.sql"),
                new ClassPathResource("db/migration/V17__glucose_alert_user_reply.sql"));
        // H2 的 CHECK 常量集合保留 DDL 会话比较器；保持迁移连接存活，事务仍使用独立连接。
        migrationConnection=ds.getConnection();
        migration.setSqlScriptEncoding("UTF-8"); migration.populate(migrationConnection);
        jdbc.update("INSERT INTO user_account(id,username,phone) VALUES(1,'alice','12345678901'),(2,'bob','12345678902')");
        jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(1,'用户甲'),(2,'用户乙')");
        repository=new GlucoseAlertRepository(jdbc); service=new GlucoseAlertService(repository);
    }
    @AfterEach void cleanup() throws Exception {
        RequestContext.clear();
        try { jdbc.execute("DROP ALL OBJECTS"); }
        finally { if(migrationConnection!=null) migrationConnection.close(); }
    }
    private void record(long id,long user,String node,String value) {
        jdbc.update("INSERT INTO glucose_record(id,user_id,record_date,node_code,glucose_value,recorded_at) VALUES(?,?,?,?,?,?)",id,user,day,node,new BigDecimal(value),at);
    }
    private GlucoseAlertDtos.Result page(String keyword,String type,String status,int pageNum,int size) {
        PageQuery query=new PageQuery();query.setPageNum(pageNum);query.setPageSize(size);
        return service.page(day,day,keyword,type,status,query);
    }
    private GlucoseAlertDtos.Handling request(GlucoseAlertDtos.Event event,String status,String note) {
        return new GlucoseAlertDtos.Handling(event.glucoseValue(),event.recordedAt(),event.version(),status,note);
    }
    private GlucoseAlertDtos.Event handle(long ops,long id,GlucoseAlertDtos.Handling request) {
        return tx.execute(ignored->service.handle(ops,id,request));
    }
    private void conflict(Runnable operation) { assertEquals(ErrorCode.CONFLICT,assertThrows(BizException.class,operation::run).getErrorCode()); }

    private GlucoseAlertDtos.Handling replyRequest(GlucoseAlertDtos.Event event,String status,String note,String reply) {
        return new GlucoseAlertDtos.Handling(event.glucoseValue(),event.recordedAt(),event.version(),status,note,reply);
    }

    @Test void publicReplyIsSeparatePersistedAndVisibleOnlyToRecordOwner() {
        record(1,1,"before_breakfast","3.8");record(2,2,"after_lunch","12");
        var handled=handle(7,1,replyRequest(repository.find(1),"PROCESSING","内部：电话沟通信息不能公开"," 请核对本次测量结果 "));
        handle(7,2,replyRequest(repository.find(2),"PROCESSING","另一用户内部备注","另一用户的回复"));
        assertEquals("请核对本次测量结果",handled.userReply());assertNotNull(handled.repliedAt());
        assertEquals("内部：电话沟通信息不能公开",handled.note());
        var page=service.userReplies(1L,new PageQuery());
        assertEquals(1,page.getTotal());assertEquals("请核对本次测量结果",page.getRecords().getFirst().userReply());
        String json=new tools.jackson.databind.ObjectMapper().writeValueAsString(page);
        for(String forbidden:List.of("note","history","operatorId","handledBy","phoneMasked","内部：电话沟通信息不能公开","另一用户"))
            assertFalse(json.contains(forbidden),forbidden);
        RequestContext.setUserId(1L);
        assertEquals(1,new UserGlucoseAlertController(service).replies(new PageQuery()).getData().getTotal());
        RequestContext.clear();
        assertThrows(BizException.class,()->new UserGlucoseAlertController(service).replies(new PageQuery()));
    }

    @Test void internalOnlyHandlingAndOldNullRepliesStayPrivate() {
        record(1,1,"before_breakfast","3.8");
        handle(7,1,request(repository.find(1),"RESOLVED","只有内部备注"));
        assertNull(repository.find(1).userReply());assertNull(repository.find(1).repliedAt());
        assertEquals(0,service.userReplies(1L,new PageQuery()).getTotal());
    }

    @Test void terminalCanPublishOrClearReplyButCannotEditInternalNoteOrReopen() {
        record(1,1,"before_breakfast","3.8");
        handle(7,1,request(repository.find(1),"RESOLVED","内部结果"));
        var published=handle(7,1,replyRequest(repository.find(1),"RESOLVED","内部结果","给用户的回复"));
        assertEquals("RESOLVED",published.status());assertEquals(1,service.userReplies(1L,new PageQuery()).getTotal());
        assertEquals("给用户的回复",repository.history(1).getFirst().userReply());
        conflict(()->handle(7,1,replyRequest(published,"PROCESSING","内部结果","尝试重开")));
        conflict(()->handle(7,1,replyRequest(published,"RESOLVED","更改内部备注","尝试更改")));
        handle(7,1,replyRequest(repository.find(1),"RESOLVED","内部结果","  "));
        assertNull(repository.find(1).userReply());assertNull(repository.find(1).repliedAt());
        assertEquals(0,service.userReplies(1L,new PageQuery()).getTotal());
        assertEquals(3,repository.history(1).size());
    }

    @Test void replyRetryDoesNotDuplicateAndOmittedFieldPreservesReplyTime() {
        record(1,1,"before_breakfast","3.8");
        var request=replyRequest(repository.find(1),"PROCESSING","内部备注","公开回复");
        var first=handle(7,1,request);assertEquals(first,handle(7,1,request));
        assertEquals(1,repository.history(1).size());
        var next=handle(7,1,request(first,"PROCESSING","仅修改内部备注"));
        assertEquals("公开回复",next.userReply());assertEquals(first.repliedAt(),next.repliedAt());
        conflict(()->handle(7,1,replyRequest(first,"PROCESSING","并发旧备注","旧版本回复")));
        assertEquals("公开回复",repository.find(1).userReply());
    }

    @Test void historicalReplyRetainsOriginalMeasurementAfterCorrectionAndSupportsPagination() {
        record(1,1,"before_breakfast","3.8");
        handle(7,1,replyRequest(repository.find(1),"RESOLVED","内部","旧测量的回复"));
        jdbc.update("UPDATE glucose_record SET glucose_value=12,recorded_at=? WHERE id=1",at.plusHours(1));
        assertNull(repository.find(1).userReply());
        handle(7,1,replyRequest(repository.find(1),"RESOLVED","内部新值","新测量的回复"));
        var query=new PageQuery();query.setPageSize(1);query.setPageNum(2);
        var page=service.userReplies(1L,query);
        assertEquals(2,page.getTotal());assertEquals("旧测量的回复",page.getRecords().getFirst().userReply());
        assertEquals(new BigDecimal("3.8"),page.getRecords().getFirst().glucoseValue());
        assertEquals(at,page.getRecords().getFirst().recordedAt());
        jdbc.update("UPDATE glucose_record SET glucose_value=6 WHERE id=1");
        assertEquals(2,service.userReplies(1L,new PageQuery()).getTotal());
        jdbc.update("UPDATE glucose_record SET is_deleted=1 WHERE id=1");
        assertEquals(0,service.userReplies(1L,new PageQuery()).getTotal());
    }

    @Test void tooLongPublicReplyIsRejectedWithoutWritingStateOrLog() {
        record(1,1,"before_breakfast","3.8");
        assertThrows(BizException.class,()->handle(7,1,replyRequest(repository.find(1),"PROCESSING","内部","字".repeat(1001))));
        assertEquals("PENDING",repository.find(1).status());assertEquals(0,repository.history(1).size());
    }

    @Test void boundariesExcludeMealsMissingInvalidAndDeletedRows() {
        record(1,1,"before_breakfast","3.8");record(2,1,"after_breakfast","3.9");
        record(3,1,"before_lunch","9.9");record(4,1,"after_lunch","10.0");
        record(5,1,"dinner","20.0");record(6,1,"before_dinner","40.0");record(7,1,"after_dinner","0");
        record(8,1,"after_dinner","20.0");jdbc.update("UPDATE glucose_record SET is_deleted=1 WHERE id=8");
        record(9,2,"after_dinner","20.0");jdbc.update("UPDATE user_account SET is_deleted=1 WHERE id=2");
        var result=page(null,null,null,1,10);
        assertEquals(2,result.page().getTotal()); assertEquals(1,result.overview().get("lowCount"));
        assertEquals(1,result.overview().get("highCount"));assertEquals(2,result.overview().get("pendingCount"));
        assertEquals(Set.of("LOW","HIGH"),new HashSet<>(result.page().getRecords().stream().map(GlucoseAlertDtos.Event::alertType).toList()));
        assertEquals("123****8901",result.page().getRecords().getFirst().phoneMasked());
    }

    @Test void keywordTypeStatusAndDateFiltersAreServerSideAndParameterized() {
        record(1,1,"before_breakfast","3.8");record(2,2,"after_lunch","10.0");
        assertEquals("1",page("用户甲","LOW","PENDING",1,10).page().getRecords().getFirst().id());
        assertEquals(0,page("' OR 1=1 --",null,null,1,10).page().getTotal());
        assertEquals(0,page("%",null,null,1,10).page().getTotal());
        assertEquals(0,page("alice","HIGH",null,1,10).page().getTotal());
        jdbc.update("UPDATE glucose_record SET record_date=? WHERE id=1",day.minusDays(1));
        assertEquals(0,page("alice",null,null,1,10).page().getTotal());
    }

    @Test void paginationOverviewCountsAllMatchedEventsNotJustPage() {
        record(1,1,"before_breakfast","3.8");record(2,2,"after_lunch","10.0");record(3,1,"after_dinner","12.0");
        var result=page(null,null,null,2,1);
        assertEquals(3,result.page().getTotal());assertEquals(3,result.page().getPages());
        assertEquals(1,result.page().getRecords().size());assertEquals("2",result.page().getRecords().getFirst().id());
        assertEquals(3,result.overview().get("total"));
    }

    @Test void handlingIsPersistentAuditedAndIdempotentWithoutChangingGlucose() {
        record(1,1,"before_breakfast","3.8");
        var original=repository.find(1);RequestContext.setRequestId("request-test");
        var input=request(original,"PROCESSING"," 已联系核对 ");
        var handled=handle(7,1,input);
        assertEquals("PROCESSING",handled.status()); assertEquals(1,handled.version());assertEquals("已联系核对",handled.note());
        assertEquals(handled,handle(7,1,input));assertEquals(1,repository.history(1).size());
        assertEquals("request-test",repository.history(1).getFirst().requestId());
        var resolved=handle(7,1,request(handled,"RESOLVED","跟进完成"));
        assertEquals("RESOLVED",resolved.status());assertEquals(2,repository.history(1).size());
        assertEquals(original.glucoseValue(),resolved.glucoseValue());assertEquals(original.recordedAt(),resolved.recordedAt());
        assertEquals(0,page(null,null,"PENDING",1,10).page().getTotal());
        assertEquals(1,page(null,null,"RESOLVED",1,10).page().getTotal());
    }

    @Test void staleVersionAndClosedEventCannotBeOverwritten() {
        record(1,1,"before_breakfast","3.8");var original=repository.find(1);
        handle(7,1,request(original,"PROCESSING","核对中"));
        conflict(()->handle(8,1,request(original,"CLOSED","其他人旧请求")));
        handle(7,1,request(repository.find(1),"CLOSED","已核对记录"));
        conflict(()->handle(7,1,request(repository.find(1),"PROCESSING","尝试重开")));
        assertEquals(2,repository.history(1).size());
    }

    @Test void changedValueOrMeasurementTimeStartsNewPendingSnapshotAndPreservesHistory() {
        record(1,1,"before_breakfast","3.8");var old=repository.find(1);
        handle(7,1,request(old,"RESOLVED","已跟进旧值"));
        jdbc.update("UPDATE glucose_record SET glucose_value=12 WHERE id=1");
        var current=repository.find(1);assertEquals("PENDING",current.status());assertEquals(0,current.version());
        conflict(()->handle(7,1,request(old,"CLOSED","旧快照")));
        handle(7,1,request(current,"RESOLVED","新值跟进完成"));
        jdbc.update("UPDATE glucose_record SET recorded_at=? WHERE id=1",at.plusHours(1));
        assertEquals("PENDING",repository.find(1).status());
        assertEquals(2,service.detail(1).history().size());
    }

    @Test void correctionToNormalAndDeletionRemoveCurrentAlertsButNotAudit() {
        record(1,1,"before_breakfast","3.8");handle(7,1,request(repository.find(1),"CLOSED","复核原记录"));
        jdbc.update("UPDATE glucose_record SET glucose_value=6 WHERE id=1");
        assertEquals(0,page(null,null,null,1,10).page().getTotal());assertEquals(1,repository.history(1).size());
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BizException.class,()->service.detail(1)).getErrorCode());
        jdbc.update("UPDATE glucose_record SET glucose_value=3.8,is_deleted=1 WHERE id=1");
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BizException.class,()->handle(7,1,new GlucoseAlertDtos.Handling(new BigDecimal("3.8"),at,0L,"CLOSED","删除后旧请求"))).getErrorCode());
        assertEquals(1,repository.history(1).size());
    }

    @Test void twoConcurrentOperatorsOnlyOneCanProcessSameVersion() throws Exception {
        record(1,1,"before_breakfast","3.8");var event=repository.find(1);
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(long ops:List.of(7L,8L)) tasks.add(pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try { handle(ops,1,request(event,"PROCESSING","操作人"+ops));return true; }
                catch(BizException error) { assertEquals(ErrorCode.CONFLICT,error.getErrorCode());return false; }
            }));
            int success=0;for(var task:tasks) if(task.get(10,TimeUnit.SECONDS))success++;
            assertEquals(1,success);assertEquals(1,repository.history(1).size());
        }
    }

    @Test void logFailureRollsBackCaseState() {
        record(1,1,"before_breakfast","3.8");var event=repository.find(1);
        jdbc.execute("ALTER TABLE gp_glucose_alert_log ADD CONSTRAINT reject_logs CHECK(note='allowed')");
        assertThrows(RuntimeException.class,()->handle(7,1,request(event,"PROCESSING","失败时不能只保存状态")));
        assertEquals("PENDING",repository.find(1).status());assertEquals(0,repository.history(1).size());
    }

    @Test void migrationGrantsViewerReadOnlyAndAdminHandling() {
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=2 AND m.perms='health:alert:view'",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=2 AND m.perms='health:alert:handle'",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=1 AND m.perms='health:alert:handle'",Long.class));
    }

    @Test void invalidFiltersAndBlankHandlingNeverWrite() {
        PageQuery query=new PageQuery();
        assertThrows(BizException.class,()->service.page(day,day.minusDays(1),null,null,null,query));
        assertThrows(BizException.class,()->service.page(day.minusDays(92),day,null,null,null,query));
        assertThrows(BizException.class,()->service.page(day,day,null,"bad",null,query));
        assertThrows(BizException.class,()->service.page(day,day,null,null,"bad",query));
        record(1,1,"before_breakfast","3.8");
        assertThrows(BizException.class,()->handle(7,1,request(repository.find(1),"PROCESSING","  ")));
        assertEquals(0,repository.history(1).size());
    }

    @Test void requestValidationAndControllerPermissionsProtectMutation() throws Exception {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(new GlucoseAlertDtos.Handling(null,null,-1L,"bad"," ")).isEmpty());
        }
        var annotation=AdminGlucoseAlertController.class.getMethod("handle",long.class,GlucoseAlertDtos.Handling.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertEquals("@ss.hasPermi('health:alert:handle')",annotation.value());
    }

    @Test void policyRejectsMealPredictionAndHandlesExactBoundaries() {
        assertNull(GlucoseAlertPolicy.classify(null,new BigDecimal("3")));
        assertNull(GlucoseAlertPolicy.classify("breakfast",new BigDecimal("20")));
        assertNull(GlucoseAlertPolicy.classify("before_breakfast",new BigDecimal("3.9")));
        assertNull(GlucoseAlertPolicy.classify("before_breakfast",new BigDecimal("9.9")));
        assertEquals("HIGH",GlucoseAlertPolicy.classify("after_breakfast",new BigDecimal("10")));
        assertEquals("LOW",GlucoseAlertPolicy.classify("before_breakfast",new BigDecimal("3.8")));
    }
}
