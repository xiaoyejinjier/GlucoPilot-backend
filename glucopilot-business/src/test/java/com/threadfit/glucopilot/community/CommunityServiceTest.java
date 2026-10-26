package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.threadfit.glucopilot.community.CommunityDtos.*;

class CommunityServiceTest {
    private JdbcTemplate jdbc;
    private CommunityService service;
    private TransactionTemplate tx;
    private java.sql.Connection migrationConnection;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:community_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,account_status INT DEFAULT 1,is_deleted INT DEFAULT 0,phone VARCHAR(20),openid VARCHAR(64))");
        jdbc.execute("CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname VARCHAR(64),avatar_url VARCHAR(512),is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE cms_news(id BIGINT PRIMARY KEY,title VARCHAR(128),summary_text VARCHAR(512),channel_code INT DEFAULT 4,content_type INT DEFAULT 1,cover_url VARCHAR(512),publish_status INT DEFAULT 1,is_deleted INT DEFAULT 0,publish_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,like_count INT DEFAULT 0,collect_count INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE news_user_action(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,news_id BIGINT,is_liked INT DEFAULT 0,is_collected INT DEFAULT 0,is_deleted INT DEFAULT 0,gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,news_id,is_deleted))");
        var migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V29__community_interactions.sql"));
        migration.setSqlScriptEncoding("UTF-8");migrationConnection=ds.getConnection();migration.populate(migrationConnection);
        var replyMigration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V32__community_comment_replies.sql"));
        replyMigration.setSqlScriptEncoding("UTF-8");replyMigration.populate(migrationConnection);
        CommunityTestSchema.applyDraftMigration(migrationConnection);
        jdbc.execute("ALTER TABLE cms_news ADD community_managed INT DEFAULT 0");
        for(long id=1;id<=4;id++) {
            jdbc.update("INSERT INTO user_account(id,phone,openid) VALUES(?,?,?)",id,"private-phone-"+id,"private-openid-"+id);
            jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(?,?)",id,"测试用户"+id);
        }
        jdbc.update("INSERT INTO cms_news(id,title) VALUES(10,'文章甲'),(11,'文章乙')");
        service=new CommunityService(jdbc);
    }
    @AfterEach void cleanup() throws Exception { RequestContext.clear();try {jdbc.execute("DROP ALL OBJECTS");} finally {migrationConnection.close();} }
    private PageQuery page() {return new PageQuery();}
    @Test void interactionNotificationsArePrivateIdempotentAndReadByLoadedCategoryRange() {
        jdbc.update("INSERT INTO community_blogger(user_id,nickname) VALUES(1,'作者')");
        jdbc.update("UPDATE cms_news SET blogger_id=(SELECT id FROM community_blogger WHERE user_id=1) WHERE id=10");
        var notices=new CommunityNotificationService(jdbc,service);
        tx.executeWithoutResult(s->{service.notifyAction(2,10,"LIKE",true);service.notifyAction(2,10,"LIKE",true);service.notifyAction(1,10,"LIKE",true);service.notifyAction(3,10,"COLLECT",true);});
        assertEquals(1,notices.summary(1).likes());assertEquals(1,notices.summary(1).collections());assertEquals(0,notices.summary(2).total());
        var likes=notices.list(1,"LIKE",page());String first=likes.getRecords().getFirst().id();assertEquals("u2",likes.getRecords().getFirst().actor().key());
        assertThrows(BizException.class,()->notices.read(2,new CommunityNotificationService.Read("LIKE",first)));
        tx.executeWithoutResult(s->service.notifyAction(4,10,"LIKE",true));
        notices.read(1,new CommunityNotificationService.Read("LIKE",first));assertEquals(1,notices.summary(1).likes());assertEquals(1,notices.summary(1).collections());
        tx.executeWithoutResult(s->service.notifyAction(4,10,"LIKE",false));assertEquals(0,notices.summary(1).likes());
        tx.execute(s->service.follow(2,"u1",true));tx.execute(s->service.follow(2,"u1",true));assertEquals(1,notices.summary(1).follows());
        tx.execute(s->service.follow(2,"u1",false));assertEquals(0,notices.summary(1).follows());
        var root=comment(2,10,"评论文章","notice-comment-1");reply(3,10,root.id(),"回复你的评论","notice-reply-01");
        assertEquals(2,notices.summary(1).comments());assertEquals(1,notices.summary(2).comments());
        service.deleteComment(2,10,Long.parseLong(root.id()));assertEquals(0,notices.summary(1).comments());assertEquals(0,notices.summary(2).comments());
        jdbc.update("UPDATE cms_news SET publish_status=2 WHERE id=10");assertEquals(0,notices.summary(1).total());
    }
    @Test void unreadCountsAllReceivedConversationsAndClearsOnlyReadRange() {
        assertEquals(new Unread(0,null,null),service.unread(1));
        Message first=send(2,1,"对方私信","unread-count-01");
        Message second=send(3,1,"另一条私信","unread-count-02");
        send(1,4,"自己发送不算未读","unread-count-03");
        assertEquals(new Unread(2,second.id(),"3"),service.unread(1));
        assertEquals(0,service.unread(4).unreadCount()-1);
        tx.executeWithoutResult(s->service.read(1,2,first.id()));
        assertEquals(1,service.unread(1).unreadCount());
        assertEquals(0,service.unread(2).unreadCount());
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=3");
        assertEquals(new Unread(0,null,null),service.unread(1));
    }
    private Message send(long from,long to,String body,String key) {return tx.execute(s->service.send(from,to,new MessageRequest(body,key)));}
    private Comment comment(long user,long news,String body,String key) {return tx.execute(s->service.addComment(user,news,new CommentRequest(body,key)));}
    private Comment reply(long user,long news,String target,String body,String key) {return tx.execute(s->service.addComment(user,news,new CommentRequest(body,key,Long.valueOf(target))));}
    private void error(ErrorCode code,Runnable task) {assertEquals(code,assertThrows(BizException.class,task::run).getErrorCode());}

    @Test void migrationAssignsEditorialPublisherWithoutPretendingItIsAUser() {
        assertEquals("社区编辑部",service.newsAuthor(1L,10).nickname());
        assertNull(service.newsAuthor(1L,10).userId());
        jdbc.update("UPDATE community_blogger SET user_id=2 WHERE id=1");
        assertEquals("2",service.newsAuthor(1L,10).userId());
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");
        assertNull(service.newsAuthor(1L,10).userId());
    }
    @Test void followsArePersistentIdempotentAndPrivateToFollower() {
        tx.execute(s->service.follow(1,"b1",true));tx.execute(s->service.follow(1,"b1",true));
        tx.execute(s->service.follow(1,"u2",true));
        assertEquals(2,service.follows(1,page()).getTotal());
        assertEquals(0,service.follows(2,page()).getTotal());
        tx.execute(s->service.follow(1,"u2",false));assertEquals(1,service.follows(1,page()).getTotal());
        error(ErrorCode.BAD_REQUEST,()->service.follow(1,"u1",true));
        error(ErrorCode.NOT_FOUND,()->service.follow(1,"u900",true));
        error(ErrorCode.NOT_FOUND,()->service.profile(1L,"u9999999999999999999"));
        jdbc.update("UPDATE user_account SET is_deleted=1 WHERE id=2");
        error(ErrorCode.NOT_FOUND,()->service.profile(1L,"u2"));
    }
    @Test void followingSearchUsesOnlyFollowedDisplayNamesWithLiteralTermsAndConsistentPages() {
        jdbc.update("UPDATE user_profile SET nickname='林医生' WHERE user_id=2");
        jdbc.update("UPDATE user_profile SET nickname='林糖友' WHERE user_id=3");
        tx.execute(s->service.follow(1,"u2",true));tx.execute(s->service.follow(1,"u3",true));tx.execute(s->service.follow(1,"b1",true));
        var q=page();q.setPageSize(1);var first=service.follows(1," 林 ",q);assertEquals(2,first.getTotal());assertEquals(2,first.getPages());
        q.setPageNum(2);assertNotEquals(first.getRecords().getFirst().key(),service.follows(1,"林",q).getRecords().getFirst().key());
        assertEquals(1,service.follows(1,"编辑",page()).getTotal());assertEquals(0,service.follows(2,"林",page()).getTotal());
        assertEquals(0,service.follows(1,"%_",page()).getTotal());assertEquals(3,service.follows(1,"",page()).getTotal());
        error(ErrorCode.BAD_REQUEST,()->service.follows(1,"x".repeat(33),page()));
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");assertEquals(1,service.follows(1,"林",page()).getTotal());
    }
    @Test void editorialBloggerAliasPreservesFollowingAndCanBeCancelledAfterAccountBinding() {
        tx.execute(s->service.follow(1,"b1",true));jdbc.update("UPDATE community_blogger SET user_id=2 WHERE id=1");
        assertEquals("u2",service.profile(1L,"b1").key());assertTrue(service.profile(1L,"u2").followed());
        assertTrue(service.follows(1,"测试用户2",page()).getRecords().getFirst().followed());
        assertFalse(tx.execute(s->service.follow(1,"b1",false)).followed());assertEquals(0,service.follows(1,page()).getTotal());
        assertTrue(tx.execute(s->service.follow(1,"b1",true)).followed());assertEquals(1,service.follows(1,page()).getTotal());
        assertEquals("u2",service.follows(1,page()).getRecords().getFirst().key());
    }
    @Test void collectionSearchOnlyMatchesOwnReadableTitlesAndUsesFilteredPagination() {
        jdbc.update("UPDATE cms_news SET title='控糖甲' WHERE id=10");jdbc.update("UPDATE cms_news SET title='控糖乙' WHERE id=11");
        jdbc.update("INSERT INTO news_user_action(user_id,news_id,is_collected) VALUES(1,10,1),(1,11,1),(2,10,1)");
        var q=page();q.setPageSize(1);var first=service.collections(1,"控糖",q);assertEquals(2,first.getTotal());assertEquals(2,first.getPages());
        q.setPageNum(2);assertNotEquals(first.getRecords().getFirst().getId(),service.collections(1,"控糖",q).getRecords().getFirst().getId());
        assertEquals(1,service.collections(2,"甲",page()).getTotal());assertEquals(0,service.collections(3,"控糖",page()).getTotal());
        assertEquals(0,service.collections(1,"%_",page()).getTotal());assertEquals(2,service.collections(1," ",page()).getTotal());
        jdbc.update("INSERT INTO community_article_hidden(news_id,user_id) VALUES(10,1)");assertEquals(1,service.collections(1,"控糖",page()).getTotal());
        jdbc.update("UPDATE cms_news SET publish_status=2 WHERE id=11");assertEquals(0,service.collections(1,"控糖",page()).getTotal());
        error(ErrorCode.BAD_REQUEST,()->service.collections(1,"x".repeat(81),page()));
    }
    @Test void searchOnlyUsesPublicNicknamesAndEscapesWildcardSemantics() throws Exception {
        assertEquals(3,service.search(1,"测试",page()).getTotal());
        assertEquals(0,service.search(1,"private-phone",page()).getTotal());
        assertEquals(0,service.search(1,"%%",page()).getTotal());
        String json=new ObjectMapper().writeValueAsString(service.search(1,"测试",page()));
        assertFalse(json.contains("private-phone"));assertFalse(json.contains("openid"));assertFalse(json.contains("accountStatus"));
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");
        assertEquals(2,service.search(1,"测试",page()).getTotal());
        error(ErrorCode.BAD_REQUEST,()->service.search(1,"测",page()));
        var invalid=page();invalid.setPageSize(51);error(ErrorCode.BAD_REQUEST,()->service.follows(1,invalid));
    }
    @Test void commentsHaveAuthorOwnershipAndRetryDoesNotDuplicate() {
        var c=comment(1,10," 第一次评论 ","comment-0001");
        assertEquals(c.id(),comment(1,10,"第一次评论","comment-0001").id());
        comment(2,10,"另一用户回复","comment-0002");
        assertEquals(2,service.commentCount(10));
        var rows=service.comments(1,10,page()).getRecords();
        assertEquals("另一用户回复",rows.getFirst().content());assertFalse(rows.getFirst().mine());
        assertTrue(rows.getLast().mine());assertEquals("u1",rows.getLast().author().key());
        error(ErrorCode.CONFLICT,()->comment(1,11,"第一次评论","comment-0001"));
        error(ErrorCode.CONFLICT,()->comment(1,10,"不同正文","comment-0001"));
        error(ErrorCode.NOT_FOUND,()->service.deleteComment(2,10,Long.parseLong(c.id())));
        service.deleteComment(1,10,Long.parseLong(c.id()));service.deleteComment(1,10,Long.parseLong(c.id()));
        assertEquals(1,service.commentCount(10));
        error(ErrorCode.CONFLICT,()->comment(1,10,"第一次评论","comment-0001"));
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");assertEquals(0,service.commentCount(10));
    }
    @Test void repliesKeepRealTargetAndAuthorAvatarUnderTheirOriginalRoot() {
        jdbc.update("UPDATE user_profile SET avatar_url='https://example.invalid/avatar.png' WHERE user_id=2");
        var root=comment(1,10,"原始评论","thread-root-001");
        var first=reply(2,10,root.id(),"回复原评论","thread-reply-01");
        var second=reply(3,10,first.id(),"回复另一位糖友","thread-reply-02");
        var page=service.comments(4,10,page());assertEquals(1,page.getTotal());assertEquals(3,service.commentCount(10));
        var thread=page.getRecords().getFirst();assertEquals(2,thread.replyCount());assertEquals(List.of(first.id(),second.id()),thread.replies().stream().map(Comment::id).toList());
        assertEquals(root.id(),second.rootCommentId());assertEquals(first.id(),second.replyToCommentId());assertEquals("u2",second.replyToAuthor().key());
        assertEquals("https://example.invalid/avatar.png",thread.replies().getFirst().author().avatarUrl());assertNotNull(first.createdAt());
    }
    @Test void repliesAreIdempotentIncludingTargetAndRejectMissingOrForeignTargets() {
        var a=comment(1,10,"评论甲","thread-root-001");var b=comment(1,10,"评论乙","thread-root-002");
        var saved=reply(2,10,a.id(),"重试内容","thread-retry-01");assertEquals(saved.id(),reply(2,10,a.id(),"重试内容","thread-retry-01").id());
        error(ErrorCode.CONFLICT,()->reply(2,10,b.id(),"重试内容","thread-retry-01"));
        error(ErrorCode.NOT_FOUND,()->reply(2,11,a.id(),"跨文章回复","thread-cross-01"));
        error(ErrorCode.NOT_FOUND,()->reply(2,10,"99999","不存在的回复","thread-missing-1"));
        error(ErrorCode.BAD_REQUEST,()->reply(2,10,"0","非法目标","thread-invalid-1"));
        service.deleteComment(2,10,Long.parseLong(saved.id()));error(ErrorCode.CONFLICT,()->reply(2,10,a.id(),"重试内容","thread-retry-01"));
    }
    @Test void replyPreviewsAreBoundedAndCursorPagesHaveNoDuplicates() {
        var root=comment(1,10,"长讨论","thread-root-001");
        for(int i=0;i<8;i++) reply(2,10,root.id(),"回复"+i,"thread-page-00"+i);
        var preview=service.comments(3,10,page()).getRecords().getFirst();assertEquals(3,preview.replies().size());assertTrue(preview.replyHasMore());assertEquals(8,preview.replyCount());
        var middle=service.replies(3,10,Long.parseLong(root.id()),Long.valueOf(preview.replyNextAfterId()),3);
        assertEquals(List.of("回复3","回复4","回复5"),middle.records().stream().map(Comment::content).toList());assertTrue(middle.hasMore());
        var last=service.replies(3,10,Long.parseLong(root.id()),Long.valueOf(middle.nextAfterId()),3);
        assertEquals(List.of("回复6","回复7"),last.records().stream().map(Comment::content).toList());assertFalse(last.hasMore());
        error(ErrorCode.NOT_FOUND,()->service.replies(3,11,Long.parseLong(root.id()),null,10));
        error(ErrorCode.BAD_REQUEST,()->service.replies(3,10,Long.parseLong(root.id()),0L,10));
        error(ErrorCode.BAD_REQUEST,()->service.replies(3,10,Long.parseLong(root.id()),null,51));
    }
    @Test void commentContextLocatesOldRootAndCollapsedReplyWithoutSkippingReplyCursor() {
        var root=comment(1,10,"通知对应的旧评论","focus-root-0001");
        var replies=new ArrayList<Comment>();for(int i=0;i<8;i++)replies.add(reply(2,10,root.id(),"回复"+i,"focus-reply-000"+i));
        for(int i=0;i<25;i++)comment(3,10,"更新的评论"+i,"focus-new-root-00"+i);
        assertTrue(service.comments(4,10,page()).getRecords().stream().noneMatch(c->c.id().equals(root.id())));
        var context=service.commentContext(4,10,Long.parseLong(replies.getLast().id()));
        assertEquals(replies.getLast().id(),context.commentId());assertEquals(root.id(),context.root().id());assertEquals(4,context.root().replies().size());assertEquals(8,context.root().replyCount());assertTrue(context.root().replyHasMore());
        assertEquals(List.of(replies.get(0).id(),replies.get(1).id(),replies.get(2).id(),replies.getLast().id()),context.root().replies().stream().map(Comment::id).toList());
        assertEquals(replies.get(2).id(),context.root().replyNextAfterId());
        assertEquals(replies.get(3).id(),service.replies(4,10,Long.parseLong(root.id()),Long.valueOf(context.root().replyNextAfterId()),3).records().getFirst().id());
        assertEquals(root.id(),service.commentContext(4,10,Long.parseLong(root.id())).commentId());
    }
    @Test void commentContextRejectsDeletedDisabledForeignAndInaccessibleTargets() {
        var root=comment(1,10,"原评论","focus-root-0001");var target=reply(2,10,root.id(),"回复","focus-reply-0001");long id=Long.parseLong(target.id());
        error(ErrorCode.NOT_FOUND,()->service.commentContext(4,11,id));
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");error(ErrorCode.NOT_FOUND,()->service.commentContext(4,10,id));jdbc.update("UPDATE user_account SET account_status=1 WHERE id=2");
        jdbc.update("INSERT INTO community_article_access(news_id,visibility) VALUES(10,'PRIVATE')");error(ErrorCode.NOT_FOUND,()->service.commentContext(4,10,id));jdbc.update("DELETE FROM community_article_access WHERE news_id=10");
        jdbc.update("UPDATE cms_news SET publish_status=2 WHERE id=10");error(ErrorCode.NOT_FOUND,()->service.commentContext(4,10,id));jdbc.update("UPDATE cms_news SET publish_status=1 WHERE id=10");
        service.deleteComment(2,10,id);error(ErrorCode.NOT_FOUND,()->service.commentContext(4,10,id));
        var another=reply(3,10,root.id(),"仍可见的回复","focus-reply-0002");service.deleteComment(1,10,Long.parseLong(root.id()));error(ErrorCode.NOT_FOUND,()->service.commentContext(4,10,Long.parseLong(another.id())));
    }
    @Test void removingRootHidesThreadButRemovingOneReplyKeepsOtherPeoplesReplies() {
        var root=comment(1,10,"原始评论","thread-root-001");var first=reply(2,10,root.id(),"中间回复","thread-child-01");
        var last=reply(3,10,first.id(),"继续讨论","thread-child-02");service.deleteComment(2,10,Long.parseLong(first.id()));
        var thread=service.comments(4,10,page()).getRecords().getFirst();assertEquals(1,thread.replyCount());assertEquals(last.id(),thread.replies().getFirst().id());assertNull(thread.replies().getFirst().replyToAuthor());
        error(ErrorCode.NOT_FOUND,()->reply(3,10,first.id(),"目标已删除","thread-child-03"));
        service.deleteComment(1,10,Long.parseLong(root.id()));assertEquals(0,service.commentCount(10));assertEquals(0,service.comments(4,10,page()).getTotal());
        error(ErrorCode.NOT_FOUND,()->service.replies(4,10,Long.parseLong(root.id()),null,10));
    }
    @Test void disabledRootAuthorHidesThreadAndPreventsNewReplies() {
        var root=comment(1,10,"原始评论","thread-root-001");reply(2,10,root.id(),"回复内容","thread-child-01");
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=1");assertEquals(0,service.commentCount(10));assertEquals(0,service.comments(3,10,page()).getTotal());
        error(ErrorCode.NOT_FOUND,()->reply(2,10,root.id(),"不可继续回复","thread-disabled-1"));
    }
    @Test void concurrentReplyRetriesCreateOnlyOneReply() throws Exception {
        var root=comment(1,10,"并发讨论","thread-root-001");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<String> task=()->{start.await();return reply(2,10,root.id(),"并发重试","thread-concurrent-1").id();};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(2,service.commentCount(10));
        } finally {pool.shutdownNow();}
    }
    @Test void unpublishedDeletedAndFutureArticlesRejectCommentsAndCollections() {
        jdbc.update("INSERT INTO news_user_action(user_id,news_id,is_collected) VALUES(1,10,1),(2,11,1)");
        assertEquals(1,service.collections(1,page()).getTotal());
        assertEquals("10",service.collections(1,page()).getRecords().getFirst().getId());
        jdbc.update("UPDATE cms_news SET publish_status=2 WHERE id=10");
        assertEquals(0,service.collections(1,page()).getTotal());
        error(ErrorCode.NOT_FOUND,()->comment(1,10,"下架文章评论","comment-0003"));
        jdbc.update("UPDATE cms_news SET publish_at=DATEADD('DAY',1,CURRENT_TIMESTAMP) WHERE id=11");
        error(ErrorCode.NOT_FOUND,()->service.comments(1,11,page()));
    }
    @Test void directMessagesAreOnlyVisibleToTheirTwoParticipants() {
        var first=send(1,2," 你好 ","message-0001");send(2,1,"你好呀","message-0002");
        send(2,3,"另一段私密内容","message-0003");
        assertEquals(2,service.messages(1,2,null,20).records().size());
        assertEquals(0,service.messages(1,3,null,20).records().size());
        assertEquals("你好",first.content());assertTrue(first.mine());
        assertEquals("1",service.messages(2,1,null,20).records().getFirst().senderId());
        assertFalse(service.messages(2,1,null,20).records().getFirst().mine());
        assertEquals(1,service.inbox(1,page()).getTotal());assertEquals(1,service.inbox(1,page()).getRecords().getFirst().unreadCount());
        assertEquals(2,service.inbox(2,page()).getTotal());
        error(ErrorCode.BAD_REQUEST,()->send(1,1,"自己","message-0004"));
        error(ErrorCode.NOT_FOUND,()->send(1,99,"陌生账号","message-0005"));
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");
        error(ErrorCode.NOT_FOUND,()->service.messages(1,2,null,20));
        assertEquals(0,service.inbox(1,page()).getTotal());
    }
    @Test void messageRetryChecksBothRecipientAndBody() {
        var first=send(1,2,"你好","message-0001");
        assertEquals(first.id(),send(1,2,"你好","message-0001").id());
        error(ErrorCode.CONFLICT,()->send(1,3,"你好","message-0001"));
        error(ErrorCode.CONFLICT,()->send(1,2,"改了正文","message-0001"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM community_message",Integer.class));
        error(ErrorCode.BAD_REQUEST,()->send(1,2,"   ","message-0002"));
        error(ErrorCode.BAD_REQUEST,()->send(1,2,"x".repeat(1001),"message-0002"));
        error(ErrorCode.BAD_REQUEST,()->send(1,2,"你好","bad"));
    }
    @Test void readMarkerDoesNotMarkLaterOrThirdPartyMessages() {
        var first=send(2,1,"第一条","message-0001");
        var later=send(2,1,"迟到消息","message-0002");
        var other=send(2,3,"他人私信","message-0003");
        service.read(1,2,first.id());service.read(1,2,first.id());
        assertNotNull(service.messages(1,2,null,20).records().getFirst().readAt());
        assertNull(service.messages(1,2,null,20).records().getLast().readAt());
        assertEquals(1,service.inbox(1,page()).getRecords().getFirst().unreadCount());
        error(ErrorCode.NOT_FOUND,()->service.read(1,2,other.id()));
        service.read(1,2,later.id());assertEquals(0,service.inbox(1,page()).getRecords().getFirst().unreadCount());
    }
    @Test void messageCursorIsExclusiveAndEachPageIsAscending() {
        for(int i=0;i<5;i++) send(1,2,"消息"+i,"message-000"+i);
        var newest=service.messages(2,1,null,2);
        assertTrue(newest.hasMore());assertEquals(List.of("消息3","消息4"),newest.records().stream().map(Message::content).toList());
        var older=service.messages(2,1,Long.valueOf(newest.nextBeforeId()),2);
        assertEquals(List.of("消息1","消息2"),older.records().stream().map(Message::content).toList());
        assertEquals(1,service.messages(2,1,Long.valueOf(older.nextBeforeId()),2).records().size());
    }
    @Test void concurrentDuplicateDeliveryStoresOneMessageAndReturnsSameId() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<String> task=()->{start.await();return send(1,2,"并发重试","message-0001").id();};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM community_message",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void controllerAuthenticationValidationAndRequestEnvelope() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new CommunityController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(401,mvc.perform(get("/api/v1/community/messages/2")).andReturn().getResponse().getStatus());
        RequestContext.setUserId(1L);RequestContext.setRequestId("community-check");
        var response=mvc.perform(post("/api/v1/community/messages/2").contentType("application/json").content("{\"content\":\"hello\",\"clientRequestId\":\"message-0001\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        var json=new ObjectMapper().readTree(response.getContentAsByteArray());assertEquals(0,json.get("code").asInt());assertEquals("community-check",json.get("requestId").asText());
        assertEquals(400,mvc.perform(post("/api/v1/news/10/comments").contentType("application/json").content("{\"content\":\"\",\"clientRequestId\":\"bad\"}")).andReturn().getResponse().getStatus());
        var root=comment(2,10,"原始评论","thread-http-root");
        var context=mvc.perform(get("/api/v1/news/10/comments/"+root.id()+"/context")).andReturn().getResponse();
        assertEquals(200,context.getStatus());var contextJson=new ObjectMapper().readTree(context.getContentAsByteArray());assertEquals(root.id(),contextJson.get("data").get("commentId").asText());assertEquals("community-check",contextJson.get("requestId").asText());
        assertEquals(404,mvc.perform(get("/api/v1/news/11/comments/"+root.id()+"/context")).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/news/10/comments").contentType("application/json").content("{\"content\":\"真实回复\",\"clientRequestId\":\"thread-http-reply\",\"replyToCommentId\":\""+root.id()+"\"}")).andReturn().getResponse().getStatus());
        var replies=mvc.perform(get("/api/v1/news/10/comments/"+root.id()+"/replies")).andReturn().getResponse();
        assertEquals(200,replies.getStatus());assertTrue(replies.getContentAsString().contains("真实回复"));
        assertEquals(404,mvc.perform(get("/api/v1/news/11/comments/"+root.id()+"/replies")).andReturn().getResponse().getStatus());
    }
    @Test void listSearchHttpKeepsAuthenticationKeywordValidationAndRequestId() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new CommunityController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(401,mvc.perform(get("/api/v1/community/following").param("keyword","用户")).andReturn().getResponse().getStatus());
        RequestContext.setUserId(1L);RequestContext.setRequestId("list-search-check");
        tx.execute(s->service.follow(1,"u2",true));jdbc.update("INSERT INTO news_user_action(user_id,news_id,is_collected) VALUES(1,10,1)");
        for(var entry:Map.of("/api/v1/community/following","用户2","/api/v1/community/collections","文章甲").entrySet()){
            var response=mvc.perform(get(entry.getKey()).param("keyword",entry.getValue())).andReturn().getResponse();assertEquals(200,response.getStatus());
            var json=new ObjectMapper().readTree(response.getContentAsByteArray());assertEquals("list-search-check",json.path("requestId").asString(""));assertEquals(1,json.path("data").path("total").asInt(-1));
        }
        assertEquals(400,mvc.perform(get("/api/v1/community/following").param("keyword","x".repeat(33))).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/api/v1/community/collections").param("keyword","x".repeat(81))).andReturn().getResponse().getStatus());
    }
}
