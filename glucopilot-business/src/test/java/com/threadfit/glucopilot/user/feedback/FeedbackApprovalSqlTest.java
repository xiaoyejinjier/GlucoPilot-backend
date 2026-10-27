package com.threadfit.glucopilot.user.feedback;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.notification.AdminWorkReminderService;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FeedbackApprovalSqlTest {
    @Test void historicalStatesPaginationReplyAndRemindersFollowActualReplies() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:feedback_approval_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        try(var schemaConnection=ds.getConnection()) {
        try(var in=getClass().getClassLoader().getResourceAsStream("db/migration/V1__init_schema.sql")) {
            String schema=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            int start=schema.indexOf("CREATE TABLE feedback (");
            schemaConnection.createStatement().execute(schema.substring(start,schema.indexOf(") ENGINE=",start)+1).replace("CONSTRAINT pk_id PRIMARY KEY","PRIMARY KEY"));
        }
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(config,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        config.setEnvironment(new Environment("test",new JdbcTransactionFactory(),ds));
        var pagination=new MybatisPlusInterceptor();pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));config.addInterceptor(pagination);
        config.addMapper(FeedbackMapper.class);
        try(var session=new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper=session.getMapper(FeedbackMapper.class);
            var admin=new AdminFeedbackService(mapper,mock(UserProfileMapper.class),mock(UserAccountMapper.class));
            var user=new FeedbackService(mapper);var reminders=new AdminWorkReminderService(jdbc);
            jdbc.update("INSERT INTO feedback(id,user_id,feedback_type,content_text,reply_status,official_reply,is_deleted) VALUES(1,9,1,'建议甲',0,NULL,0),(2,9,1,'建议乙',1,'已回复乙',0),(3,9,2,'问题丙',3,'  ',0),(4,9,2,'问题丁',2,'已回复丁',0),(5,9,1,'删除记录',0,NULL,0)");
            jdbc.update("UPDATE feedback SET is_deleted=1,deleted_at=CURRENT_TIMESTAMP WHERE id=5");
            var q=new PageQuery();q.setPageSize(1);
            var pending=admin.page(null,0,null,q);assertEquals(2,pending.getTotal());assertEquals(2,pending.getPages());assertEquals("1",pending.getRecords().getFirst().getId());
            q.setPageNum(2);assertEquals("3",admin.page(null,0,null,q).getRecords().getFirst().getId());q.setPageNum(1);
            assertEquals(2,admin.page(null,2,null,q).getTotal());assertEquals(2,admin.page(null,2,null,q).getRecords().getFirst().getReplyStatus());
            assertEquals(1,admin.page(2,0,"丙",q).getTotal());
            assertEquals(2,reminders.summary(Set.of("feedback:manage")).total());
            var request=new AdminFeedbackDtos.ReplyRequest();request.setReplyStatus(1);request.setOfficialReply(" 已审批回复 ");
            assertTrue(admin.reply(7L,1L,request).getUpdated());
            var stored=mapper.selectById(1L);assertEquals(2,stored.getReplyStatus());assertEquals("已审批回复",stored.getOfficialReply());assertEquals(7L,stored.getRepliedBy());assertNotNull(stored.getRepliedAt());
            assertEquals(1,admin.page(null,0,null,q).getTotal());assertEquals(3,admin.page(null,2,null,q).getTotal());assertEquals(1,reminders.summary(Set.of("feedback:manage")).total());
            var all=new PageQuery();all.setPageSize(20);
            var mine=user.page(9L,all);assertEquals(4,mine.getTotal());assertTrue(mine.getRecords().stream().filter(f->f.getId().equals("2")).allMatch(f->f.getReplyStatus()==2));
            assertEquals(0,user.page(10L,all).getTotal());
            for(Integer oldStatus:new Integer[]{null,0,1,2,3}) {
                request.setReplyStatus(oldStatus);request.setOfficialReply("  ");assertThrows(BizException.class,()->admin.reply(7L,1L,request));
            }
            assertEquals("已审批回复",mapper.selectById(1L).getOfficialReply());
            request.setReplyStatus(null);request.setOfficialReply("无需选择状态也可以回复");admin.reply(7L,3L,request);
            assertEquals(0,reminders.summary(Set.of("feedback:manage")).total());
            assertThrows(BizException.class,()->admin.page(null,1,null,all));
        } finally { jdbc.execute("DROP ALL OBJECTS"); }
        }
    }
}
