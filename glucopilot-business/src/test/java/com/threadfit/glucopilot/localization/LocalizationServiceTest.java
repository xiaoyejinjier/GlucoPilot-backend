package com.threadfit.glucopilot.localization;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.CommunityService;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.threadfit.glucopilot.localization.LocalizationDtos.*;

class LocalizationServiceTest {
    private JdbcTemplate jdbc;
    private LocalizationService service;
    private java.sql.Connection migrationConnection;
    private final AtomicInteger calls=new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:locale_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,account_status INT DEFAULT 1,is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname VARCHAR(64),avatar_url VARCHAR(512),bio VARCHAR(160) DEFAULT '',is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE cms_news(id BIGINT PRIMARY KEY,title VARCHAR(128),summary_text VARCHAR(512),content_body TEXT,publish_status INT DEFAULT 1,publish_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,is_deleted INT DEFAULT 0,community_managed INT DEFAULT 1,blogger_id BIGINT DEFAULT 1)");
        jdbc.execute("CREATE TABLE community_blogger(id BIGINT PRIMARY KEY,user_id BIGINT,enabled INT DEFAULT 1)");
        jdbc.execute("CREATE TABLE community_article(news_id BIGINT PRIMARY KEY,state VARCHAR(24),author_user_id BIGINT)");
        jdbc.execute("CREATE TABLE community_article_access(news_id BIGINT PRIMARY KEY,visibility VARCHAR(16))");
        jdbc.execute("CREATE TABLE community_article_reader(news_id BIGINT,user_id BIGINT)");
        jdbc.execute("CREATE TABLE community_article_hidden(news_id BIGINT,user_id BIGINT)");
        jdbc.execute("CREATE TABLE community_follow(follower_id BIGINT,target_key VARCHAR(32),following INT)");
        migrationConnection=ds.getConnection();var migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V39__english_content_cache.sql"));migration.setSqlScriptEncoding("UTF-8");migration.populate(migrationConnection);
        jdbc.update("INSERT INTO user_account(id) VALUES(1),(2),(3)");
        jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(1,'小林'),(2,'用户_abc123'),(3,'Alice')");
        jdbc.update("INSERT INTO cms_news(id,title,summary_text,content_body) VALUES(10,'文章甲','摘要甲','正文 5.2 mmol/L')");
        jdbc.update("INSERT INTO community_article VALUES(10,'PUBLISHED',1)");
        service=new LocalizationService(jdbc,new CommunityService(jdbc),(kind,source)->{calls.incrementAndGet();var out=new LinkedHashMap<String,String>();source.forEach((key,value)->out.put(key,"English "+value));return out;},new ObjectMapper());
    }
    @AfterEach void close() throws Exception {service.close();try{jdbc.execute("DROP ALL OBJECTS");}finally{migrationConnection.close();}}
    private Request request(String kind,String id,boolean retry){return new Request(List.of(new Item(kind,id)),retry);}
    private Result get(long user,String kind,String id){return service.localize(user,request(kind,id,false)).getFirst();}
    private Result ready(long user,String kind,String id) throws Exception {
        for(int n=0;n<100;n++){var result=get(user,kind,id);if(result.status().equals("READY"))return result;Thread.sleep(10);}throw new AssertionError("Translation not completed");
    }
    @Test void cachesBySourceAndKeepsArticleAndNicknameOriginals() throws Exception {
        assertEquals("PENDING",get(2,"ARTICLE_PREVIEW","10").status());var result=ready(2,"ARTICLE_PREVIEW","10");
        assertEquals("文章甲",result.source().get("title"));assertEquals("English 文章甲",result.translated().get("title"));
        int before=calls.get();assertEquals(result.sourceHash(),get(3,"ARTICLE_PREVIEW","10").sourceHash());assertEquals(before,calls.get());
        jdbc.update("UPDATE cms_news SET title='文章乙' WHERE id=10");var changed=ready(2,"ARTICLE_PREVIEW","10");assertNotEquals(result.sourceHash(),changed.sourceHash());
        assertEquals("文章乙",jdbc.queryForObject("SELECT title FROM cms_news WHERE id=10",String.class));
        var name=ready(2,"PROFILE_NAME","u1");assertEquals("小林",name.source().get("nickname"));assertEquals("小林",jdbc.queryForObject("SELECT nickname FROM user_profile WHERE user_id=1",String.class));
        jdbc.update("UPDATE user_profile SET nickname='小李' WHERE user_id=1");assertNotEquals(name.sourceHash(),ready(2,"PROFILE_NAME","u1").sourceHash());
    }
    @Test void correctedPromptVersionDoesNotReuseOldFailedTranslations() throws Exception {
        String oldSource=new ObjectMapper().writeValueAsString(new TreeMap<>(Map.of("title","文章甲","summary","摘要甲")));
        var oldHashes=new ArrayList<String>();
        for(String version:List.of("en-v1:","en-v2:")){
            String oldHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((version+oldSource).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            oldHashes.add(oldHash);
            jdbc.update("INSERT INTO content_localization(kind,resource_id,source_hash,locale,status) VALUES('ARTICLE_PREVIEW','10',?,'en','FAILED')",oldHash);
        }
        var result=ready(2,"ARTICLE_PREVIEW","10");
        assertEquals(1,calls.get());
        for(String oldHash:oldHashes){assertNotEquals(oldHash,result.sourceHash());assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM content_localization WHERE source_hash=?",String.class,oldHash));}
        assertEquals("文章甲",jdbc.queryForObject("SELECT title FROM cms_news WHERE id=10",String.class));
    }
    @Test void failedShortTextBatchSplitsOnceAndKeepsHealthyArticlesReady() throws Exception {
        jdbc.update("INSERT INTO cms_news(id,title,summary_text,content_body) VALUES(11,'文章乙','摘要乙','正文乙')");
        jdbc.update("INSERT INTO community_article VALUES(11,'PUBLISHED',1)");
        service.close();var count=new AtomicInteger();
        service=new LocalizationService(jdbc,new CommunityService(jdbc),(kind,source)->{
            count.incrementAndGet();
            if(source.containsKey("j0_title")||"文章甲".equals(source.get("title")))throw new IllegalArgumentException("Untranslatable item");
            return Map.of("title","Healthy article","summary","Healthy summary");
        },new ObjectMapper());
        var initial=service.localize(2,new Request(List.of(new Item("ARTICLE_PREVIEW","10"),new Item("ARTICLE_PREVIEW","11")),false));
        assertTrue(initial.stream().allMatch(result->result.status().equals("PENDING")));
        assertEquals("Healthy article",ready(2,"ARTICLE_PREVIEW","11").translated().get("title"));
        assertEquals("FAILED",get(2,"ARTICLE_PREVIEW","10").status());
        for(int n=0;n<5;n++){assertEquals("READY",get(2,"ARTICLE_PREVIEW","11").status());assertEquals("FAILED",get(2,"ARTICLE_PREVIEW","10").status());}
        assertEquals(3,count.get());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_localization WHERE lease_token IS NOT NULL",Integer.class));
        assertEquals("文章乙",jdbc.queryForObject("SELECT title FROM cms_news WHERE id=11",String.class));
    }
    @Test void shutdownOfFailedBatchNeverStartsIndividualFallbacks() throws Exception {
        jdbc.update("INSERT INTO cms_news(id,title,summary_text,content_body) VALUES(11,'文章乙','摘要乙','正文乙')");
        jdbc.update("INSERT INTO community_article VALUES(11,'PUBLISHED',1)");
        service.close();var entered=new CountDownLatch(1);var count=new AtomicInteger();
        service=new LocalizationService(jdbc,new CommunityService(jdbc),(kind,source)->{
            count.incrementAndGet();entered.countDown();
            try{new CountDownLatch(1).await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            throw new IllegalStateException("Stopped");
        },new ObjectMapper());
        service.localize(2,new Request(List.of(new Item("ARTICLE_PREVIEW","10"),new Item("ARTICLE_PREVIEW","11")),false));
        assertTrue(entered.await(1,TimeUnit.SECONDS));service.close();
        assertEquals(1,count.get());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM content_localization WHERE status='PENDING' AND lease_token IS NULL",Integer.class));
    }
    @Test void cachedTranslationNeverBypassesVisibilityHiddenModerationOrDisabledAccount() throws Exception {
        ready(2,"ARTICLE_PREVIEW","10");
        jdbc.update("INSERT INTO community_article_hidden VALUES(10,2)");var hidden=get(2,"ARTICLE_PREVIEW","10");assertEquals("UNAVAILABLE",hidden.status());assertTrue(hidden.source().isEmpty());assertTrue(hidden.translated().isEmpty());assertEquals("",hidden.sourceHash());
        jdbc.update("INSERT INTO community_article_access VALUES(10,'PRIVATE')");assertEquals("UNAVAILABLE",get(3,"ARTICLE_PREVIEW","10").status());assertEquals("READY",get(1,"ARTICLE_PREVIEW","10").status());
        jdbc.update("UPDATE community_article SET state='PENDING_ADMIN' WHERE news_id=10");assertEquals("UNAVAILABLE",get(1,"ARTICLE_PREVIEW","10").status());
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");assertThrows(BizException.class,()->get(2,"PROFILE_NAME","u1"));
    }
    @Test void publishedEditorialArticleCanTranslateWithoutAUserAuthorAndDisablingPublisherRevokesCache() throws Exception {
        jdbc.update("INSERT INTO community_blogger(id) VALUES(1)");
        jdbc.update("UPDATE community_article SET author_user_id=NULL WHERE news_id=10");
        assertEquals("READY",ready(2,"ARTICLE_PREVIEW","10").status());
        assertEquals("READY",ready(2,"ARTICLE_BODY","10").status());
        jdbc.update("UPDATE community_blogger SET enabled=0 WHERE id=1");
        assertEquals("UNAVAILABLE",get(2,"ARTICLE_PREVIEW","10").status());
        assertEquals("UNAVAILABLE",get(2,"ARTICLE_BODY","10").status());
    }
    @Test void friendsRequireCurrentMutualFollowEvenForAnExistingCache() throws Exception {
        jdbc.update("INSERT INTO community_article_access VALUES(10,'FRIENDS')");jdbc.update("INSERT INTO community_article_reader VALUES(10,2)");
        jdbc.update("INSERT INTO community_follow VALUES(1,'u2',1),(2,'u1',1)");ready(2,"ARTICLE_BODY","10");
        jdbc.update("UPDATE community_follow SET following=0 WHERE follower_id=1");assertEquals("UNAVAILABLE",get(2,"ARTICLE_BODY","10").status());
        assertEquals("UNAVAILABLE",get(3,"ARTICLE_BODY","10").status());
    }
    @Test void generatedNamesAndAlreadyEnglishNamesDoNotCallAi() {
        assertEquals("User_abc123",get(1,"PROFILE_NAME","u2").translated().get("nickname"));assertEquals("Alice",get(1,"PROFILE_NAME","u3").translated().get("nickname"));assertEquals(0,calls.get());
        assertThrows(BizException.class,()->get(1,"PROFILE_NAME","1"));assertThrows(BizException.class,()->get(1,"ARTICLE_BODY","u1"));
        assertThrows(BizException.class,()->service.localize(1,new Request(List.of(new Item("ARTICLE_PREVIEW","10"),new Item("PROFILE_NAME","999")),false)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_localization",Integer.class));
    }
    @Test void repeatedPollsUseOneLeaseAndFailuresNeedExplicitRetry() throws Exception {
        service.close();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var count=new AtomicInteger();
        service=new LocalizationService(jdbc,new CommunityService(jdbc),(kind,source)->{count.incrementAndGet();started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}throw new IllegalStateException("Unavailable");},new ObjectMapper());
        get(2,"ARTICLE_PREVIEW","10");assertTrue(started.await(1,TimeUnit.SECONDS));for(int n=0;n<5;n++)get(2,"ARTICLE_PREVIEW","10");assertEquals(1,count.get());release.countDown();
        for(int n=0;n<100&&!get(2,"ARTICLE_PREVIEW","10").status().equals("FAILED");n++)Thread.sleep(10);
        assertEquals("FAILED",get(2,"ARTICLE_PREVIEW","10").status());assertEquals(1,count.get());
        // DATETIME(0) 可能把当前时刻舍入到下一秒，明确过期后再验证人工重试。
        jdbc.update("UPDATE content_localization SET retry_after=?",java.time.LocalDateTime.now().minusSeconds(2));service.localize(2,request("ARTICLE_PREVIEW","10",true));
        for(int n=0;n<100&&count.get()<2;n++)Thread.sleep(10);assertEquals(2,count.get());
    }
    @Test void normalShutdownReleasesPendingJobsForImmediateRecovery() throws Exception {
        service.close();var entered=new CountDownLatch(1);
        service=new LocalizationService(jdbc,new CommunityService(jdbc),(kind,source)->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}throw new IllegalStateException("Stopped");},new ObjectMapper());
        get(2,"ARTICLE_PREVIEW","10");assertTrue(entered.await(1,TimeUnit.SECONDS));service.close();
        service=new LocalizationService(jdbc,new CommunityService(jdbc),(kind,source)->Map.of("title","Recovered title","summary","Recovered summary"),new ObjectMapper());
        assertEquals("Recovered title",ready(2,"ARTICLE_PREVIEW","10").translated().get("title"));
    }
}
