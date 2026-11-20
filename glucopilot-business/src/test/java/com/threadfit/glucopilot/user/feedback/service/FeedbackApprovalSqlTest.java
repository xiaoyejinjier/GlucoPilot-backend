package com.threadfit.glucopilot.user.feedback.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mybatis.MyBatisTestMappers;
import com.threadfit.glucopilot.notification.mapper.AdminWorkReminderMapper;
import com.threadfit.glucopilot.notification.service.AdminWorkReminderService;
import com.threadfit.glucopilot.user.dto.AdminFeedbackDtos;
import com.threadfit.glucopilot.user.mapper.*;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

class FeedbackApprovalSqlTest {
    @Test
    void historicalStatesPaginationReplyAndRemindersFollowActualReplies() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:feedback_approval_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        try (var schemaConnection = ds.getConnection()) {
            try (var in =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream("db/migration/V1__init_schema.sql")) {
                String schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                int start = schema.indexOf("CREATE TABLE feedback (");
                schemaConnection
                        .createStatement()
                        .execute(
                                schema.substring(start, schema.indexOf(") ENGINE=", start) + 1)
                                        .replace("CONSTRAINT pk_id PRIMARY KEY", "PRIMARY KEY"));
            }
            schemaConnection
                    .createStatement()
                    .execute("ALTER TABLE feedback ADD device_id BIGINT NULL");
            schemaConnection
                    .createStatement()
                    .execute("ALTER TABLE feedback ADD device_description VARCHAR(120) NULL");
            schemaConnection
                    .createStatement()
                    .execute(
                            "CREATE TABLE feedback_type(id TINYINT PRIMARY KEY,name"
                                    + " VARCHAR(32),device_related TINYINT,enabled TINYINT,sort_no"
                                    + " INT)");
            jdbc.update(
                    "INSERT INTO feedback_type"
                        + " VALUES(1,'产品建议',0,1,10),(2,'Bug问题',0,1,20),(4,'其他',0,1,40),(5,'设备支持',1,1,50)");
            try (var in =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream(
                                    "db/migration/V84__feedback_device_support_sort.sql")) {
                schemaConnection
                        .createStatement()
                        .execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            schemaConnection
                    .createStatement()
                    .execute("ALTER TABLE feedback DROP CONSTRAINT chk_fb_type");
            schemaConnection
                    .createStatement()
                    .execute(
                            "CREATE TABLE user_device(id BIGINT PRIMARY KEY,user_id"
                                + " BIGINT,device_name VARCHAR(64),provider VARCHAR(32),is_deleted"
                                + " TINYINT DEFAULT 0)");
            schemaConnection
                    .createStatement()
                    .execute(
                            "ALTER TABLE feedback ADD CONSTRAINT fk_feedback_type FOREIGN"
                                    + " KEY(feedback_type) REFERENCES feedback_type(id)");
            schemaConnection
                    .createStatement()
                    .execute(
                            "ALTER TABLE feedback ADD CONSTRAINT fk_feedback_device FOREIGN"
                                + " KEY(device_id) REFERENCES user_device(id) ON DELETE SET NULL");
            schemaConnection
                    .createStatement()
                    .execute(
                            "CREATE TABLE admin_work_reminder_read(ops_id BIGINT,kind"
                                    + " VARCHAR(32),source_id BIGINT,revision VARCHAR(255),read_at"
                                    + " TIMESTAMP(3),PRIMARY KEY(ops_id,kind,source_id,revision))");
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            GlobalConfigUtils.setGlobalConfig(
                    config,
                    new GlobalConfig()
                            .setDbConfig(new GlobalConfig.DbConfig())
                            .setMetaObjectHandler(
                                    new com.threadfit.glucopilot.common.mybatis
                                            .AuditMetaObjectHandler()));
            config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
            var pagination = new MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            config.addInterceptor(pagination);
            config.addMapper(FeedbackMapper.class);
            config.addMapper(FeedbackTypeMapper.class);
            try (var session =
                    new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                var mapper = session.getMapper(FeedbackMapper.class);
                var types = new FeedbackTypeService(session.getMapper(FeedbackTypeMapper.class));
                var admin =
                        new AdminFeedbackService(
                                mapper,
                                mock(UserProfileMapper.class),
                                mock(UserAccountMapper.class),
                                types);
                var user = new FeedbackService(mapper, types);
                var reminders =
                        new AdminWorkReminderService(
                                MyBatisTestMappers.mapper(jdbc, AdminWorkReminderMapper.class));
                jdbc.update(
                        "INSERT INTO"
                            + " feedback(id,user_id,feedback_type,content_text,reply_status,official_reply,is_deleted)"
                            + " VALUES(1,9,1,'建议甲',0,NULL,0),(2,9,1,'建议乙',1,'已回复乙',0),(3,9,2,'问题丙',3,'"
                            + "  ',0),(4,9,2,'问题丁',2,'已回复丁',0),(5,9,1,'删除记录',0,NULL,0)");
                jdbc.update(
                        "UPDATE feedback SET is_deleted=1,deleted_at=CURRENT_TIMESTAMP WHERE id=5");
                var q = new PageQuery();
                q.setPageSize(1);
                var pending = admin.page(null, 0, null, q);
                assertEquals(2, pending.getTotal());
                assertEquals(2, pending.getPages());
                assertEquals("1", pending.getRecords().getFirst().getId());
                q.setPageNum(2);
                assertEquals("3", admin.page(null, 0, null, q).getRecords().getFirst().getId());
                q.setPageNum(1);
                assertEquals(2, admin.page(null, 2, null, q).getTotal());
                assertEquals(
                        2, admin.page(null, 2, null, q).getRecords().getFirst().getReplyStatus());
                assertEquals(1, admin.page(2, 0, "丙", q).getTotal());
                assertEquals(2, reminders.summary(7L, Set.of("feedback:manage")).total());
                var request = new AdminFeedbackDtos.ReplyRequest();
                request.setReplyStatus(1);
                request.setOfficialReply(" 已审批回复 ");
                assertTrue(admin.reply(7L, 1L, request).getUpdated());
                var stored = mapper.selectById(1L);
                assertEquals(2, stored.getReplyStatus());
                assertEquals("已审批回复", stored.getOfficialReply());
                assertEquals(7L, stored.getRepliedBy());
                assertNotNull(stored.getRepliedAt());
                assertEquals(1, admin.page(null, 0, null, q).getTotal());
                assertEquals(3, admin.page(null, 2, null, q).getTotal());
                assertEquals(1, reminders.summary(7L, Set.of("feedback:manage")).total());
                var all = new PageQuery();
                all.setPageSize(20);
                var mine = user.page(9L, all);
                assertEquals(4, mine.getTotal());
                assertTrue(
                        mine.getRecords().stream()
                                .filter(f -> f.getId().equals("2"))
                                .allMatch(f -> f.getReplyStatus() == 2));
                assertEquals(0, user.page(10L, all).getTotal());
                assertEquals(
                        java.util.List.of(1, 2, 5, 4),
                        types.enabledTypes().stream()
                                .map(com.threadfit.glucopilot.user.dto.FeedbackDtos.TypeItem::getId)
                                .toList());
                assertTrue(types.requireEnabled(5).getDeviceRelated());
                var deviceRequest =
                        new com.threadfit.glucopilot.user.dto.FeedbackDtos.SubmitRequest();
                deviceRequest.setFeedbackType(5);
                deviceRequest.setContentText("同步失败");
                deviceRequest.setDeviceId(100L);
                jdbc.update("INSERT INTO user_device VALUES(100,9,'血糖仪X2','MOCK',0)");
                user.submit(9L, deviceRequest);
                var deviceFeedback = admin.page(5, null, null, all).getRecords().getFirst();
                assertEquals("设备支持", deviceFeedback.getFeedbackTypeName());
                assertEquals("100", deviceFeedback.getDeviceId());
                assertEquals("血糖仪X2（MOCK）", deviceFeedback.getDeviceDescription());
                jdbc.update("UPDATE user_device SET user_id=NULL WHERE id=100");
                assertThrows(BizException.class, () -> user.submit(9L, deviceRequest));
                jdbc.update("UPDATE user_device SET user_id=10 WHERE id=100");
                assertThrows(BizException.class, () -> user.submit(9L, deviceRequest));
                jdbc.update("DELETE FROM user_device WHERE id=100");
                var historical = user.page(9L, all).getRecords().getFirst();
                assertNull(historical.getDeviceId());
                assertEquals("血糖仪X2（MOCK）", historical.getDeviceDescription());
                assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update("UPDATE feedback SET feedback_type=99 WHERE id=1"));
                assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update("UPDATE feedback SET device_id=999 WHERE id=1"));
                for (Integer oldStatus : new Integer[] {null, 0, 1, 2, 3}) {
                    request.setReplyStatus(oldStatus);
                    request.setOfficialReply("  ");
                    assertThrows(BizException.class, () -> admin.reply(7L, 1L, request));
                }
                assertEquals("已审批回复", mapper.selectById(1L).getOfficialReply());
                request.setReplyStatus(null);
                request.setOfficialReply("无需选择状态也可以回复");
                admin.reply(7L, 3L, request);
                admin.reply(7L, Long.valueOf(deviceFeedback.getId()), request);
                assertEquals(0, reminders.summary(7L, Set.of("feedback:manage")).total());
                assertThrows(BizException.class, () -> admin.page(null, 1, null, all));
                // 仅修改字典：新分类无需枚举或前端代码就能提交、筛选、回复和显示名称。
                jdbc.update("INSERT INTO feedback_type VALUES(6,'账号问题',0,1,25)");
                assertEquals(6, types.enabledTypes().get(2).getId());
                var customRequest =
                        new com.threadfit.glucopilot.user.dto.FeedbackDtos.SubmitRequest();
                customRequest.setFeedbackType(6);
                customRequest.setContentText("账号相关反馈");
                var custom = user.submit(9L, customRequest);
                assertEquals(
                        "账号问题",
                        admin.page(6, null, null, all)
                                .getRecords()
                                .getFirst()
                                .getFeedbackTypeName());
                admin.reply(7L, Long.valueOf(custom.getId()), request);
                jdbc.update("UPDATE feedback_type SET enabled=0,name='账号相关' WHERE id=6");
                assertThrows(BizException.class, () -> user.submit(9L, customRequest));
                assertEquals(
                        "账号相关", user.page(9L, all).getRecords().getFirst().getFeedbackTypeName());
            } finally {
                schemaConnection.createStatement().execute("DROP ALL OBJECTS");
            }
        }
    }
}
