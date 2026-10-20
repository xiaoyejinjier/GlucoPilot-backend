package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import static com.threadfit.glucopilot.community.PublicationDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AdminCommunityArticlesTest {
    private JdbcTemplate jdbc;
    private CommunityPublicationService service;
    private Connection migrationConnection;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:admin_articles_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);migrationConnection=ds.getConnection();
        String schema=new ClassPathResource("db/migration/V1__init_schema.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String table:List.of("cms_news","news_user_action","media_file")) {
            int start=schema.indexOf("CREATE TABLE "+table+" (");
            String ddl=schema.substring(start,schema.indexOf(";",start)).replace("CONSTRAINT pk_id PRIMARY KEY","PRIMARY KEY");
            try(var statement=migrationConnection.createStatement()) { statement.execute(ddl); }
        }
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,account_status INT DEFAULT 1,is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname VARCHAR(64),avatar_url VARCHAR(512),is_deleted INT DEFAULT 0)");
        for(String file:List.of("V22__community_interactions.sql","V24__community_publication_moderation.sql","V25__community_comment_replies.sql","V26__community_article_images.sql")) {
            var resource=new ClassPathResource("db/migration/"+file);
            var migration=new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(resource.getContentAsString(StandardCharsets.UTF_8).replace("DROP CHECK chk_mf_biz","DROP CONSTRAINT chk_mf_biz").getBytes(StandardCharsets.UTF_8)));
            migration.setSqlScriptEncoding("UTF-8");migration.populate(migrationConnection);
        }
        CommunityTestSchema.applyV27(migrationConnection);
        jdbc.update("INSERT INTO user_account(id) VALUES(2)");
        jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(2,'用户小明')");
        article(10,"官方已发布",0,1);article(11,"官方草稿",0,0);article(12,"官方下架",0,2);
        article(20,"用户等待审核",1,0);article(21,"用户已发布",1,1);article(22,"已删除文章",1,1);
        jdbc.update("UPDATE cms_news SET is_deleted=1,deleted_at=CURRENT_TIMESTAMP,deleted_by=99 WHERE id=22");
        jdbc.update("INSERT INTO community_article(news_id,author_user_id,state,ai_verdict) VALUES(20,2,'PENDING_ADMIN','PASS'),(21,2,'PUBLISHED','PASS'),(22,2,'PUBLISHED','PASS')");
        service=new CommunityPublicationService(jdbc,mock(CmsNewsMapper.class),new CommunityService(jdbc));
    }
    private void article(long id,String title,int managed,int status) {
        jdbc.update("INSERT INTO cms_news(id,title,summary_text,content_body,channel_code,publish_at,publish_status,community_managed) VALUES(?,?,?,'完整正文',4,CURRENT_TIMESTAMP,?,?)",id,title,"文章摘要",status,managed);
    }
    @AfterEach void cleanup() throws Exception { try { jdbc.execute("DROP ALL OBJECTS"); } finally { migrationConnection.close(); } }
    private PageResult<Article> list(String state,String source,String keyword) { return service.adminArticles(state,source,keyword,new PageQuery()); }
    private CommunityDraftService drafts(){return new CommunityDraftService(jdbc,new com.threadfit.glucopilot.common.util.JsonUtils(new tools.jackson.databind.ObjectMapper()),service);}
    @Test void draftReadAndListAcceptMysqlDatetimeWithoutTimestampCast() {
        var saved=tx().execute(s->drafts().create(2,draft("MySQL datetime regression","draft-mysql-time",null)));
        var mysql=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                var rows=super.queryForList(sql,args);
                for(var row:rows)if(row.get("updated_at") instanceof java.sql.Timestamp time)row.put("updated_at",time.toLocalDateTime());
                return rows;
            }
        };
        var drafts=new CommunityDraftService(mysql,new com.threadfit.glucopilot.common.util.JsonUtils(new tools.jackson.databind.ObjectMapper()),service);
        assertEquals(saved.updatedAt(),drafts.get(2,Long.parseLong(saved.id())).updatedAt());
        assertEquals(saved.id(),drafts.list(2,new PageQuery()).getRecords().getFirst().id());
    }
    @Test void draftHttpAcceptsMiniProgramPayloadWithEmptySourceAndMedia() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CommunityDraftController(drafts())).build();
        com.threadfit.glucopilot.common.context.RequestContext.setUserId(2L);
        try {
            var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/community/drafts")
                    .contentType("application/json").content("{\"title\":\"draft title\",\"body\":\"unfinished\",\"tagCodes\":[\"diet\"],\"customTags\":[],\"imageMediaIds\":[],\"videoMediaIds\":[],\"sourceArticleId\":null,\"sourceRevision\":null,\"revision\":null,\"clientRequestId\":\"draft-http-001\"}"))
                    .andReturn();
            assertEquals(200,result.getResponse().getStatus(),()->String.valueOf(result.getResolvedException()));
            assertEquals(1,drafts().list(2,new PageQuery()).getTotal());
        }finally{com.threadfit.glucopilot.common.context.RequestContext.clear();}
    }
    private CommunityDraftService.Payload draft(String body,String key,Integer revision){return new CommunityDraftService.Payload("我的草稿",body,List.of("diet"),List.of(),List.of(),key,revision,21L,1);}
    @Test void draftsSaveIncompleteContentWithoutChangingPublishedArticleAndEnforceOwnerRevision() {
        var drafts=drafts();var first=tx().execute(s->drafts.create(2,draft("还没写完","draft-save-001",null)));
        assertEquals(1,jdbc.queryForObject("SELECT publish_status FROM cms_news WHERE id=21",Integer.class));
        assertEquals("PUBLISHED",service.get(2L,21,false).state());
        assertEquals(first.id(),tx().execute(s->drafts.create(2,draft("还没写完","draft-save-001",null))).id());
        long id=Long.parseLong(first.id());
        var request=draft("继续编辑了草稿","draft-edit-001",1);var edited=tx().execute(s->drafts.edit(2,id,request));assertEquals(2,edited.revision());
        assertEquals(2,tx().execute(s->drafts.edit(2,id,request)).revision());
        assertThrows(BizException.class,()->tx().execute(s->drafts.edit(2,id,draft("另一个修改","draft-edit-002",1))));
        assertThrows(BizException.class,()->drafts.get(3,id));
        assertThrows(BizException.class,()->tx().execute(s->{drafts.delete(2,id,1);return null;}));
        tx().executeWithoutResult(s->drafts.delete(2,id,2));assertThrows(BizException.class,()->drafts.get(2,id));
    }
    @Test void draftPublishingIsAtomicIdempotentAndStillRequiresBothReviews() {
        var drafts=drafts();var saved=tx().execute(s->drafts.create(2,draft("今天认真记录了自己的早餐和运动情况，希望分享坚持记录的经验。","draft-publish-01",null)));
        long id=Long.parseLong(saved.id());var request=new CommunityDraftService.Publish(1,"draft-publish-op");
        var article=tx().execute(s->drafts.publish(2,id,request));assertEquals("21",article.id());assertEquals("AI_PENDING",article.state());assertEquals(2,article.revision());
        assertEquals(0,jdbc.queryForObject("SELECT publish_status FROM cms_news WHERE id=21",Integer.class));
        assertEquals("21",tx().execute(s->drafts.publish(2,id,request)).id());
        assertEquals(0,drafts.list(2,new PageQuery()).getTotal());
        assertThrows(BizException.class,()->drafts.get(2,id));
        jdbc.update("UPDATE community_article SET state='AI_REJECTED',ai_verdict='REJECT' WHERE news_id=21");
        assertEquals(0,drafts.list(2,new PageQuery()).getTotal());
        assertEquals("AI_REJECTED",service.mine(2,new PageQuery()).getRecords().stream().filter(a->a.id().equals("21")).findFirst().orElseThrow().state());
        var edited=tx().execute(s->service.edit(2,21,new Submit("审核后重新修改","今天认真补充了早餐和运动的真实记录，希望和大家交流坚持记录的小经验。",List.of("diet"),List.of(),"rejected-edit-01",2)));
        assertEquals("AI_PENDING",edited.state());assertEquals(0,drafts.list(2,new PageQuery()).getTotal());
        assertThrows(BizException.class,()->tx().execute(s->drafts.publish(2,id,new CommunityDraftService.Publish(1,"different-publish"))));
    }
    @Test void invalidOrStaleDraftPublishPreservesSavedDraftAndNeverOverwritesArticle() {
        var drafts=drafts();var saved=tx().execute(s->drafts.create(2,draft("短内容","draft-invalid-01",null)));long id=Long.parseLong(saved.id());
        assertThrows(BizException.class,()->tx().execute(s->drafts.publish(2,id,new CommunityDraftService.Publish(1,"draft-invalid-p1"))));
        assertEquals(1,drafts.list(2,new PageQuery()).getTotal());assertEquals(1,jdbc.queryForObject("SELECT publish_status FROM cms_news WHERE id=21",Integer.class));
        jdbc.update("UPDATE community_article SET revision=2 WHERE news_id=21");
        tx().execute(s->drafts.edit(2,id,draft("今天认真记录了自己的早餐和运动情况，希望分享坚持记录的经验。","draft-invalid-e1",1)));
        assertThrows(BizException.class,()->tx().execute(s->drafts.publish(2,id,new CommunityDraftService.Publish(2,"draft-invalid-p2"))));
        assertEquals(1,drafts.list(2,new PageQuery()).getTotal());
    }
    private void image(long id,long owner,int biz) {
        jdbc.update("INSERT INTO media_file(id,user_id,biz_type,bucket_name,storage_key,file_url,mime_type,size_bytes) VALUES(?,?,?,'test-bucket','test-key','unused','image/png',4)",id,owner,biz);
    }
    @Test void imageEditsPersistOrderAndIdempotencyAndRequireOwnedAvailableArticlePhotos() {
        image(50,2,7);image(51,2,7);image(52,3,7);image(53,2,3);
        String body="今天认真记录了自己的早餐和日常情况，希望和大家分享坚持记录的经验。";
        var request=new Submit("我的配图文章",body,List.of("diet"),List.of(),"image-edit-001",1,List.of("51","50"));
        var saved=tx().execute(s->service.edit(2,20,request));
        assertEquals(List.of("51","50"),saved.images().stream().map(ArticleImage::mediaId).toList());
        assertEquals(2,saved.revision());assertEquals("AI_PENDING",saved.state());
        assertEquals(2,tx().execute(s->service.edit(2,20,request)).revision());
        var changed=new Submit("我的配图文章",body,List.of("diet"),List.of(),"image-edit-001",1,List.of("50","51"));
        assertThrows(BizException.class,()->tx().execute(s->service.edit(2,20,changed)));
        for(var ids:List.of(List.of("52"),List.of("53"),List.of("50","50"),List.of("999"))) {
            var invalid=new Submit("我的配图文章",body,List.of("diet"),List.of(),"image-edit-002",2,ids);
            assertThrows(BizException.class,()->tx().execute(s->service.edit(2,20,invalid)));
        }
        assertEquals(List.of("51","50"),service.get(2L,20,false).images().stream().map(ArticleImage::mediaId).toList());
        var removed=tx().execute(s->service.edit(2,20,new Submit("我的配图文章",body,List.of("diet"),List.of(),"image-edit-003",2,List.of())));
        assertTrue(removed.images().isEmpty());
    }
    @Test void pendingImagesAreOwnerOnlyAndAdminMustReadReferencedCurrentArticle() {
        image(50,2,7);jdbc.update("INSERT INTO user_account(id) VALUES(3)");
        jdbc.update("INSERT INTO community_article_image(news_id,media_id,sort_no) VALUES(20,50,0)");
        var mapper=mock(com.threadfit.glucopilot.file.mapper.MediaFileMapper.class);
        var storage=mock(com.threadfit.glucopilot.file.storage.ObjectStorageService.class);
        var photo=new com.threadfit.glucopilot.file.entity.MediaFile();photo.setId(50L);photo.setUserId(2L);photo.setBizType(7);photo.setFileStatus(1);photo.setIsDeleted(0);photo.setMimeType("image/png");photo.setSizeBytes(4L);photo.setStorageProvider(1);photo.setBucketName("test-bucket");photo.setStorageKey("test-key");
        org.mockito.Mockito.when(mapper.selectById(50L)).thenReturn(photo);
        org.mockito.Mockito.when(storage.provider()).thenReturn(com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider.MINIO);
        org.mockito.Mockito.when(storage.bucket()).thenReturn("test-bucket");
        org.mockito.Mockito.when(storage.getObject("test-key")).thenReturn(new byte[]{1,2,3,4});
        var images=new CommunityArticleImageService(jdbc,mapper,storage);
        assertEquals(4,images.read(2L,null,50).bytes().length);
        assertThrows(BizException.class,()->images.read(3L,null,50));
        assertEquals(4,images.read(null,20L,50).bytes().length);
        assertThrows(BizException.class,()->images.read(null,21L,50));
        jdbc.update("UPDATE cms_news SET publish_status=1 WHERE id=20");
        jdbc.update("UPDATE community_article SET state='PUBLISHED' WHERE news_id=20");
        assertEquals(4,images.read(3L,null,50).bytes().length);
        jdbc.update("UPDATE community_article SET state='HIDDEN' WHERE news_id=20");
        assertThrows(BizException.class,()->images.read(3L,null,50));
        photo.setBizType(3);assertThrows(BizException.class,()->images.read(2L,null,50));
        photo.setBizType(7);photo.setSizeBytes(5242881L);assertThrows(BizException.class,()->images.read(2L,null,50));
    }
    @Test void videoOnlyPostPersistsThroughDraftPublishAndEnforcesOwnershipAndIdempotency() {
        image(60,2,8);image(61,3,8);jdbc.update("UPDATE media_file SET mime_type='video/mp4' WHERE id IN (60,61)");
        var request=new Submit("视频记录","",List.of("daily"),List.of(),"video-edit-001",1,List.of(),List.of("60"));
        var saved=tx().execute(s->service.edit(2,20,request));
        assertEquals("AI_PENDING",saved.state());assertTrue(saved.images().isEmpty());assertEquals("60",saved.videos().getFirst().mediaId());
        assertEquals(2,tx().execute(s->service.edit(2,20,request)).revision());
        assertThrows(BizException.class,()->tx().execute(s->service.edit(2,20,new Submit("视频记录","",List.of("daily"),List.of(),"video-edit-002",2,List.of(),List.of("61")))));
        var p=new CommunityDraftService.Payload("视频草稿","",List.of("daily"),List.of(),List.of(),"video-draft-01",null,21L,1,List.of("60"));
        var draft=tx().execute(s->drafts().create(2,p));assertEquals("60",draft.videos().getFirst().mediaId());
        var article=tx().execute(s->drafts().publish(2,Long.parseLong(draft.id()),new CommunityDraftService.Publish(1,"video-publish-01")));
        assertEquals("60",article.videos().getFirst().mediaId());assertTrue(article.body().isEmpty());assertEquals("AI_PENDING",article.state());
    }
    @Test void authorFeedNeverIncludesPendingHiddenOrDeletedArticlesAndTagsStayOwnerScoped() {
        var page=service.byAuthor(2,"u2",new PageQuery());assertEquals(List.of("21"),page.getRecords().stream().map(Article::id).toList());
        jdbc.update("INSERT INTO community_tag(code,name) VALUES('custom_test','我的专用标签')");
        jdbc.update("INSERT INTO community_article_tag(news_id,tag_id) SELECT 20,id FROM community_tag WHERE code='custom_test'");
        assertTrue(service.myTags(2).stream().anyMatch(t->t.code().equals("custom_test")));
        jdbc.update("INSERT INTO user_account(id) VALUES(3)");assertTrue(service.myTags(3).isEmpty());
        jdbc.update("UPDATE community_article SET state='HIDDEN' WHERE news_id=21");assertEquals(0,service.byAuthor(2,"u2",new PageQuery()).getTotal());
    }
    @Test void defaultIncludesOfficialAndCommunityAtAllStatesWithRealPublishers() {
        var page=list(null,null,null);
        assertEquals(5,page.getTotal());
        assertEquals(List.of("21","20","12","11","10"),page.getRecords().stream().map(Article::id).toList());
        var user=page.getRecords().getFirst();assertEquals("COMMUNITY",user.source());assertEquals("用户小明",user.author().nickname());assertEquals("u2",user.author().key());
        var official=page.getRecords().getLast();assertEquals("OFFICIAL",official.source());assertEquals("社区编辑部",official.author().nickname());assertEquals("b1",official.author().key());
        assertNotNull(official.createdAt());assertEquals("",official.body());
    }
    @Test void filtersStatusSourceTitleAndPublisherWithoutSqlWildcards() {
        assertEquals(2,list("PUBLISHED",null,null).getTotal());
        assertEquals("11",list("DRAFT",null,null).getRecords().getFirst().id());
        assertEquals("12",list("HIDDEN",null,null).getRecords().getFirst().id());
        assertEquals(2,list(null,"COMMUNITY",null).getTotal());assertEquals(3,list(null,"OFFICIAL",null).getTotal());
        assertEquals("20",list(null,null,"等待").getRecords().getFirst().id());
        assertEquals(2,list(null,null,"小明").getTotal());assertEquals(3,list(null,null,"编辑部").getTotal());
        assertEquals(0,list(null,null,"%_'").getTotal());
    }
    @Test void detailLoadsUnpublishedContentAndDoesNotMisattributeInactiveAuthor() {
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");
        jdbc.update("UPDATE user_profile SET nickname='新昵称' WHERE user_id=2");
        var item=service.get(null,20,true);
        assertEquals("完整正文",item.body());assertEquals("PENDING_ADMIN",item.state());assertEquals("新昵称",item.author().nickname());assertEquals("u2",item.author().key());
        assertThrows(BizException.class,()->service.get(9L,20,false));
        assertThrows(BizException.class,()->service.get(null,22,true));
    }
    @Test void pagingTotalsAndInvalidFiltersAreValidated() {
        var q=new PageQuery();q.setPageSize(2);q.setPageNum(2);
        var page=service.adminArticles(null,null,null,q);assertEquals(5,page.getTotal());assertEquals(List.of("12","11"),page.getRecords().stream().map(Article::id).toList());
        assertThrows(BizException.class,()->list("INVALID",null,null));assertThrows(BizException.class,()->list(null,"INVALID",null));
        assertThrows(BizException.class,()->list(null,null,"字".repeat(81)));
        q.setPageSize(51);assertThrows(BizException.class,()->service.adminArticles(null,null,null,q));
    }
    @Test void adminReviewPreservesAiGateAndVersionThenRecordsPublisherAndOperator() {
        assertThrows(BizException.class,()->service.review(99,20,new Review(2,"APPROVE","通过")));
        jdbc.update("UPDATE community_article SET ai_verdict='PENDING' WHERE news_id=20");
        assertThrows(BizException.class,()->service.review(99,20,new Review(1,"APPROVE","通过")));
        jdbc.update("UPDATE community_article SET ai_verdict='PASS' WHERE news_id=20");
        var item=service.review(99,20,new Review(1,"APPROVE","核查通过"));assertEquals("PUBLISHED",item.state());assertEquals("u2",item.author().key());
        assertEquals(99,jdbc.queryForObject("SELECT reviewed_by FROM community_article WHERE news_id=20",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM community_moderation_log WHERE actor_id=99 AND decision='APPROVE'",Integer.class));
        assertThrows(BizException.class,()->service.review(99,10,new Review(1,"APPROVE","通过")));
    }
    @Test void reportQueueLoadsArticleContextAndPersistsAdministrativeResolution() {
        jdbc.update("INSERT INTO community_report(reporter_id,target_type,target_id,reason,details,client_request_id) VALUES(2,'ARTICLE',10,'MISINFORMATION','核查请求','report-admin-01')");
        var page=service.reports("OPEN",new PageQuery());assertEquals(1,page.getTotal());
        assertTrue(page.getRecords().getFirst().get("target_content").toString().contains("官方已发布"));
        long id=Long.parseLong(page.getRecords().getFirst().get("id").toString());
        service.resolveReport(99,id,new Resolution("DISMISS","核查后未发现问题"));
        assertEquals(0,service.reports("OPEN",new PageQuery()).getTotal());assertEquals(1,service.reports("DISMISSED",new PageQuery()).getTotal());
    }
    private TransactionTemplate tx() { return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))); }
    @Test void customTagsPersistWithEditsAndRetriesButStayOutOfPublicNavigationUntilPublication() {
        var request=new Submit("我的饮食记录","今天把一餐的搭配认真记了下来，希望和大家交流坚持记录的经验。",List.of("diet"),List.of(" #我的早餐 "),"custom-edit-01",1);
        var saved=tx().execute(s->service.edit(2,20,request));
        assertEquals(2,saved.tags().size());assertTrue(saved.tags().stream().anyMatch(t->t.name().equals("我的早餐")));
        String code=saved.tags().stream().filter(t->t.name().equals("我的早餐")).findFirst().orElseThrow().code();
        assertTrue(code.startsWith("custom_"));assertEquals(6,service.tags().size());
        var retry=tx().execute(s->service.edit(2,20,request));assertEquals(saved.revision(),retry.revision());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM community_tag WHERE code=?",Integer.class,code));
        jdbc.update("UPDATE community_article SET state='PUBLISHED' WHERE news_id=20");jdbc.update("UPDATE cms_news SET publish_status=1 WHERE id=20");
        assertTrue(service.tags().stream().anyMatch(t->t.code().equals(code)));
    }
    @Test void customTagValidationAndVersionConflictsCannotLeakNewTags() {
        String body="今天把一餐的搭配认真记了下来，希望和大家交流坚持记录的经验。";
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM community_tag",Long.class);
        assertThrows(BizException.class,()->tx().execute(s->service.edit(2,20,new Submit("分享记录",body,List.of(),List.of("新标签"),"custom-invalid-1",9))));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM community_tag",Long.class));
        for(var custom:List.of(List.of("<script>"),List.of("字".repeat(17)),List.of("标签甲","标签乙","标签丙","标签丁")))
            assertThrows(BizException.class,()->tx().execute(s->service.edit(2,20,new Submit("分享记录",body,List.of(),custom,"custom-invalid-2",1))));
        jdbc.update("UPDATE community_tag SET enabled=0 WHERE code='diet'");
        assertThrows(BizException.class,()->tx().execute(s->service.edit(2,20,new Submit("分享记录",body,List.of(),List.of("饮食控糖"),"custom-invalid-3",1))));
    }
    @Test void realMapperSubmissionStoresCustomTagsAndIdempotencyThenAuthorIdentityStaysConsistent() {
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(configuration,new com.baomidou.mybatisplus.core.config.GlobalConfig()
                .setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()).setMetaObjectHandler(new com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler()));
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),jdbc.getDataSource()));
        configuration.addMapper(CmsNewsMapper.class);
        var template=new org.mybatis.spring.SqlSessionTemplate(new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration));
        var community=new CommunityService(jdbc);var posting=new CommunityPublicationService(jdbc,template.getMapper(CmsNewsMapper.class),community);
        var request=new Submit("新投稿分享","今天认真记录了自己的早餐，希望能和大家分享一下坚持记录的经验。",List.of("diet"),List.of("我的早餐"),"real-submit-001",null);
        var first=tx().execute(s->posting.submit(2,request));var retry=tx().execute(s->posting.submit(2,request));
        assertEquals(first.id(),retry.id());assertEquals("AI_PENDING",first.state());assertEquals(2,first.tags().size());
        assertEquals("u2",community.newsAuthor(null,Long.parseLong(first.id())).key());
        assertEquals("用户小明",community.newsAuthor(null,Long.parseLong(first.id())).nickname());
        assertEquals(1,posting.mine(2,new PageQuery()).getRecords().stream().filter(a->a.id().equals(first.id())).count());
        assertFalse(posting.feed(2,null,false,null,new PageQuery()).getRecords().stream().anyMatch(a->a.id().equals(first.id())));
        assertThrows(BizException.class,()->posting.get(9L,Long.parseLong(first.id()),false));
    }
    @Test void sameCustomNameSharesStableCodeAcrossArticlesAndNormalizesBuiltinNames() {
        String body="今天把一餐的搭配认真记了下来，希望和大家交流坚持记录的经验。";
        var first=tx().execute(s->service.edit(2,20,new Submit("分享记录",body,List.of(),List.of(" ＡＢＣ ","饮食控糖"),"custom-shared-1",1)));
        var second=tx().execute(s->service.edit(2,21,new Submit("分享记录",body,List.of(),List.of("abc"),"custom-shared-2",1)));
        assertTrue(first.tags().stream().anyMatch(t->t.code().equals("diet")));
        assertEquals(first.tags().stream().filter(t->t.code().startsWith("custom_")).findFirst().orElseThrow().code(),second.tags().getFirst().code());
        assertEquals(7,jdbc.queryForObject("SELECT COUNT(*) FROM community_tag",Integer.class));
    }
    @Test void moderationQueueRequiresMatchingVersionAndNeverPublishesByItself() {
        var queue=new CommunityModerationQueue(jdbc,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),service);
        jdbc.update("UPDATE community_article SET state='AI_PENDING' WHERE news_id=20");
        var old=queue.claim();assertNotNull(old);assertNull(queue.claim());
        jdbc.update("UPDATE community_article SET revision=2,state='AI_PENDING' WHERE news_id=20");
        var current=queue.claim();assertFalse(queue.finish(old,"PASS","旧结果","model"));
        assertTrue(queue.finish(current,"PASS","初审通过，等待终审","model"));
        assertEquals("PENDING_ADMIN",service.get(null,20,true).state());assertEquals(0,jdbc.queryForObject("SELECT publish_status FROM cms_news WHERE id=20",Integer.class));
        assertFalse(queue.finish(current,"PASS","重复结果","model"));
    }
    @Test void interruptedModerationBecomesRetryableErrorInsteadOfAutomaticPass() {
        var queue=new CommunityModerationQueue(jdbc,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),service);
        jdbc.update("UPDATE community_article SET state='AI_REVIEWING',updated_at=? WHERE news_id=20",java.time.LocalDateTime.now().minusMinutes(10));
        assertNull(queue.claim());assertEquals("AI_ERROR",service.get(null,20,true).state());
        tx().executeWithoutResult(s->service.retry(2,20,false));assertNotNull(queue.claim());
    }
}
