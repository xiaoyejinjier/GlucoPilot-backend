package com.threadfit.glucopilot.content.service;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import com.threadfit.glucopilot.community.CommunityService;
import com.threadfit.glucopilot.content.dto.ContentDtos;
import com.threadfit.glucopilot.content.mapper.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ContentActionConcurrencyTest {
    @Test void realMappersSerializeFirstLikeAndKeepCountsAcrossConcurrentUsers() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:actions_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String schema;
        try(var input=getClass().getResourceAsStream("/db/migration/V1__init_schema.sql")) { schema=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8); }
        try(var schemaConnection=ds.getConnection()) {
        for(String table:List.of("cms_news","news_user_action")) {
            int start=schema.indexOf("CREATE TABLE "+table+" (");int end=schema.indexOf(";",start);
            String ddl=schema.substring(start,end).replace("CONSTRAINT pk_id PRIMARY KEY","PRIMARY KEY");
            try(var statement=schemaConnection.createStatement()) {statement.execute(ddl);}
        }
        try(var statement=schemaConnection.createStatement()) {statement.execute("ALTER TABLE cms_news ADD blogger_id BIGINT DEFAULT 1 NOT NULL");}
        try(var statement=schemaConnection.createStatement()) {statement.execute("ALTER TABLE cms_news ADD community_managed TINYINT DEFAULT 0 NOT NULL");}
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,account_status INT DEFAULT 1,is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE community_blogger(id BIGINT PRIMARY KEY,user_id BIGINT)");
        com.threadfit.glucopilot.community.CommunityTestSchema.applyDraftMigration(schemaConnection);
        for(long id=1;id<=10;id++)jdbc.update("INSERT INTO user_account(id) VALUES(?)",id);
        jdbc.update("INSERT INTO cms_news(id,title,channel_code,publish_at,publish_status) VALUES(1,'并发文章',4,CURRENT_TIMESTAMP,1)");
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(configuration,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new AuditMetaObjectHandler()));
        configuration.setEnvironment(new Environment("test",new SpringManagedTransactionFactory(),ds));
        configuration.addMapper(CmsNewsMapper.class);configuration.addMapper(NewsUserActionMapper.class);
        var template=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var service=new ContentService(mock(CmsBannerMapper.class),template.getMapper(CmsNewsMapper.class),template.getMapper(NewsUserActionMapper.class),new CommunityService(jdbc));
        var pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> work=new ArrayList<>();
            for(int i=0;i<20;i++) {long user=i%10+1;work.add(pool.submit(()->tx.execute(s->{var req=new ContentDtos.NewsActionRequest();req.setLiked(true);req.setCollected(true);return service.updateNewsAction(user,1L,req);})) );}
            for(var task:work) task.get(20,TimeUnit.SECONDS);
            assertEquals(10,jdbc.queryForObject("SELECT like_count FROM cms_news WHERE id=1",Integer.class));
            assertEquals(10,jdbc.queryForObject("SELECT collect_count FROM cms_news WHERE id=1",Integer.class));
            assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM news_user_action WHERE is_liked=1 AND is_collected=1",Integer.class));
            tx.execute(s->{var req=new ContentDtos.NewsActionRequest();req.setLiked(false);return service.updateNewsAction(1L,1L,req);});
            assertEquals(9,jdbc.queryForObject("SELECT like_count FROM cms_news WHERE id=1",Integer.class));
            jdbc.update("INSERT INTO community_article(news_id,author_user_id,state) VALUES(1,1,'PUBLISHED')");
            jdbc.update("UPDATE cms_news SET community_managed=1 WHERE id=1");
            jdbc.update("INSERT INTO community_article_access(news_id,visibility) VALUES(1,'PRIVATE')");
            assertEquals(0,service.pageNews(null,new com.threadfit.glucopilot.common.api.PageQuery(),2L).getRecords().size());
            assertEquals(0,service.pageNews(null,new com.threadfit.glucopilot.common.api.PageQuery()).getRecords().size());
            assertEquals(1,service.pageNews(null,new com.threadfit.glucopilot.common.api.PageQuery(),1L).getRecords().size());
        } finally {pool.shutdownNow();jdbc.execute("DROP ALL OBJECTS");}
        }
    }
}
