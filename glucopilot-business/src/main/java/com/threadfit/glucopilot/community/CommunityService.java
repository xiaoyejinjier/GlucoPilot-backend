package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.content.dto.ContentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import static com.threadfit.glucopilot.community.CommunityDtos.*;

@Service
@RequiredArgsConstructor
public class CommunityService {
    private final JdbcTemplate jdbc;
    private static final String USER_FROM = " FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id AND p.is_deleted=0 ";
    private static final String ACTIVE = "u.is_deleted=0 AND u.account_status=1";
    private static final String USER_FIELDS = "u.id,COALESCE(NULLIF(p.nickname,''),'社区用户') nickname,COALESCE(p.avatar_url,'') avatar_url";
    private static final String VISIBLE_COMMENT = " c.is_deleted=0 AND EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id AND " + ACTIVE + ") AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM user_account u WHERE u.id=r.user_id AND "+ACTIVE+"))) ";

    private BizException missing() { return new BizException(ErrorCode.NOT_FOUND,"内容或用户不存在"); }
    private void requireUser(long userId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user_account u WHERE u.id=? AND "+ACTIVE,Long.class,userId)!=1) throw missing();
    }
    public void requireReadable(Long viewer,long newsId) { new ArticleAccessService(jdbc).requireReadable(viewer,newsId); }
    public long viewCount(long newsId) { return new ArticleAccessService(jdbc).views(newsId); }
    private void requirePeer(long viewer,long peer) { requireUser(viewer);requireUser(peer);if(viewer==peer) throw new BizException(ErrorCode.BAD_REQUEST,"不能给自己发私信"); }
    private void page(PageQuery q) {
        if(q.getPageNum()<1 || q.getPageSize()<1 || q.getPageSize()>50) throw new BizException(ErrorCode.BAD_REQUEST,"每页条数应为1～50");
    }
    private boolean following(Long viewer,String key) {
        if(viewer==null)return false;
        if(key.startsWith("u"))return jdbc.queryForObject("SELECT COUNT(*) FROM community_follow WHERE follower_id=? AND following=1 AND (target_key=? OR target_key IN (SELECT CONCAT('b',id) FROM community_blogger WHERE user_id=? AND enabled=1))",Long.class,viewer,key,Long.parseLong(key.substring(1)))>0;
        return jdbc.queryForObject("SELECT COUNT(*) FROM community_follow WHERE follower_id=? AND target_key=? AND following=1",Long.class,viewer,key)>0;
    }
    public Profile profile(Long viewer,String key) {
        if(key==null || !key.matches("[ub][1-9][0-9]{0,18}")) throw missing();
        long id;
        try { id=Long.parseLong(key.substring(1)); } catch(NumberFormatException ex) { throw missing(); }
        if(key.charAt(0)=='u') {
            var rows=jdbc.query("SELECT "+USER_FIELDS+",COALESCE(p.bio,'') bio"+USER_FROM+" WHERE u.id=? AND "+ACTIVE,
                    (rs,n)->new Profile(key,Long.toString(id),rs.getString("nickname"),rs.getString("avatar_url"),rs.getString("bio"),following(viewer,key),Objects.equals(viewer,id)),id);
            if(rows.isEmpty()) throw missing();
            return rows.getFirst();
        }
        var rows=jdbc.query("SELECT b.*,u.id linked_id FROM community_blogger b LEFT JOIN user_account u ON u.id=b.user_id AND "+ACTIVE+" WHERE b.id=? AND b.enabled=1",
                (rs,n)->{long linked=rs.getLong("linked_id");String user=rs.wasNull()?null:Long.toString(linked);
                    return new Profile(key,user,rs.getString("nickname"),rs.getString("avatar_url"),rs.getString("bio"),following(viewer,key),user!=null && Objects.equals(viewer,linked));},id);
        if(rows.isEmpty()) throw missing();
        var publisher=rows.getFirst();
        return publisher.userId()==null?publisher:profile(viewer,"u"+publisher.userId());
    }
    public Profile newsAuthor(Long viewer,long newsId) {
        var linked=jdbc.queryForList("SELECT b.user_id FROM cms_news n JOIN community_blogger b ON b.id=n.blogger_id JOIN user_account u ON u.id=b.user_id AND "+ACTIVE+" WHERE n.id=? AND n.is_deleted=0",Long.class,newsId);
        if(!linked.isEmpty()) return profile(viewer,"u"+linked.getFirst());
        Long blogger=jdbc.queryForObject("SELECT blogger_id FROM cms_news WHERE id=? AND is_deleted=0",Long.class,newsId);
        try { return profile(viewer,"b"+blogger); } catch(BizException ex) { return null; }
    }
    @Transactional
    public Profile follow(long viewer,String key,boolean desired) {
        if(jdbc.queryForList("SELECT id FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1 FOR UPDATE",Long.class,viewer).isEmpty())throw missing();
        Profile target=profile(viewer,key);
        if(target.isSelf()) throw new BizException(ErrorCode.BAD_REQUEST,"不能关注自己");
        String canonical=target.key();
        jdbc.update("INSERT INTO community_follow(follower_id,target_key,following) VALUES(?,?,?) ON DUPLICATE KEY UPDATE following=?,updated_at=CURRENT_TIMESTAMP",viewer,canonical,desired?1:0,desired?1:0);
        if(target.userId()!=null)jdbc.update("UPDATE community_follow SET following=0,updated_at=CURRENT_TIMESTAMP WHERE follower_id=? AND target_key IN (SELECT CONCAT('b',id) FROM community_blogger WHERE user_id=?) AND following=1",viewer,Long.parseLong(target.userId()));
        if(target.userId()!=null && target.followed()!=desired) {
            long recipient=Long.parseLong(target.userId());
            if(desired) notifyEvent(recipient,viewer,"FOLLOW",recipient,null,null);
            else jdbc.update("DELETE FROM community_notification WHERE recipient_id=? AND actor_id=? AND kind='FOLLOW'",recipient,viewer);
        }
        return profile(viewer,key);
    }
    private static final String VALID_FOLLOW = " FROM community_follow f WHERE f.follower_id=? AND f.following=1 AND ((LEFT(f.target_key,1)='u' AND EXISTS(SELECT 1 FROM user_account u WHERE CONCAT('u',u.id)=f.target_key AND "+ACTIVE+")) OR (LEFT(f.target_key,1)='b' AND EXISTS(SELECT 1 FROM community_blogger b WHERE CONCAT('b',b.id)=f.target_key AND b.enabled=1))) ";
    public PageResult<Profile> follows(long viewer,PageQuery q) {
        return follows(viewer,null,q);
    }
    public PageResult<Profile> follows(long viewer,String keyword,PageQuery q) {
        requireUser(viewer);page(q);
        String term=listKeyword(keyword,32);
        String from=VALID_FOLLOW+" AND (EXISTS(SELECT 1 FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id AND p.is_deleted=0 WHERE f.target_key=CONCAT('u',u.id) AND LOCATE(?,COALESCE(NULLIF(p.nickname,''),'社区用户'))>0) OR EXISTS(SELECT 1 FROM community_blogger b LEFT JOIN user_profile bp ON bp.user_id=b.user_id AND bp.is_deleted=0 WHERE f.target_key=CONCAT('b',b.id) AND LOCATE(?,CASE WHEN b.user_id IS NULL THEN b.nickname ELSE COALESCE(NULLIF(bp.nickname,''),'社区用户') END)>0))";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,viewer,term,term);
        var keys=jdbc.queryForList("SELECT f.target_key"+from+" ORDER BY f.updated_at DESC,f.target_key LIMIT ? OFFSET ?",String.class,viewer,term,term,q.getPageSize(),q.offset());
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,keys.stream().map(key->profile(viewer,key)).toList());
    }
    public PageResult<Profile> search(long viewer,String keyword,PageQuery q) {
        requireUser(viewer);page(q);String term=keyword==null?"":keyword.trim();
        if(term.length()<2 || term.length()>32) throw new BizException(ErrorCode.BAD_REQUEST,"请输入2～32字的昵称");
        // Literal substring search; no phone/openid matching and no SQL wildcard interpretation.
        String filter=USER_FROM+" WHERE "+ACTIVE+" AND u.id<>? AND LOCATE(?,COALESCE(p.nickname,''))>0";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+filter,Long.class,viewer,term);
        var ids=jdbc.queryForList("SELECT u.id"+filter+" ORDER BY u.id DESC LIMIT ? OFFSET ?",Long.class,viewer,term,q.getPageSize(),q.offset());
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,ids.stream().map(id->profile(viewer,"u"+id)).toList());
    }
    public PageResult<ContentDtos.NewsListItem> collections(long viewer,PageQuery q) {
        return collections(viewer,null,q);
    }
    private String listKeyword(String keyword,int max) {
        String term=Objects.toString(keyword,"").trim();
        if(term.length()>max)throw new BizException(ErrorCode.BAD_REQUEST,"搜索词最多"+max+"字");
        return term;
    }
    public PageResult<ContentDtos.NewsListItem> collections(long viewer,String keyword,PageQuery q) {
        return actionArticles(viewer,keyword,q,false);
    }
    public PageResult<ContentDtos.NewsListItem> likes(long viewer,String keyword,PageQuery q) {
        return actionArticles(viewer,keyword,q,true);
    }
    private PageResult<ContentDtos.NewsListItem> actionArticles(long viewer,String keyword,PageQuery q,boolean liked) {
        requireUser(viewer);page(q);
        String term=listKeyword(keyword,80);
        String from=" FROM news_user_action a JOIN cms_news n ON n.id=a.news_id WHERE a.user_id=? AND a.is_deleted=0 AND "+(liked?"a.is_liked=1":"a.is_collected=1")+" AND "+ArticleAccessService.publishedSql("n")+" AND "+ArticleAccessService.readableSql("n",viewer)+" AND LOCATE(?,n.title)>0";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,viewer,term);
        var rows=jdbc.query("SELECT n.*"+from+" ORDER BY a.gmt_modified DESC,n.id DESC LIMIT ? OFFSET ?",(rs,i)->ContentDtos.NewsListItem.builder()
                .id(rs.getString("id")).title(rs.getString("title")).summaryText(rs.getString("summary_text"))
                .channelCode(rs.getInt("channel_code")).contentType(rs.getInt("content_type")).coverUrl(rs.getString("cover_url"))
                .publishAt(rs.getTimestamp("publish_at").toLocalDateTime()).likeCount(rs.getInt("like_count")).collectCount(rs.getInt("collect_count")).viewCount(viewCount(rs.getLong("id"))).build(),viewer,term,q.getPageSize(),q.offset());
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,rows);
    }
    public long commentCount(long newsId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM community_comment c WHERE c.news_id=? AND "+VISIBLE_COMMENT,Long.class,newsId);
    }
    private Comment comment(long viewer,ResultSet rs) throws SQLException {
        return comment(viewer,rs,true);
    }
    private Comment comment(long viewer,ResultSet rs,boolean includeReplies) throws SQLException {
        long user=rs.getLong("user_id"),id=rs.getLong("id"),news=rs.getLong("news_id");
        Long root=number(rs.getObject("root_comment_id")),target=number(rs.getObject("reply_to_comment_id"));
        Profile targetAuthor=null;
        if(target!=null) {
            var authors=jdbc.queryForList("SELECT c.user_id FROM community_comment c WHERE c.id=? AND c.news_id=? AND "+VISIBLE_COMMENT,Long.class,target,news);
            if(!authors.isEmpty()) targetAuthor=profile(viewer,"u"+authors.getFirst());
        }
        Replies page=includeReplies && root==null?replyPage(viewer,news,id,null,3):new Replies(List.of(),false,null);
        long total=includeReplies && root==null?replyCount(news,id):0;
        return new Comment(Long.toString(id),rs.getString("content"),time(rs,"created_at"),profile(viewer,"u"+user),user==viewer,
                Long.toString(root==null?id:root),target==null?null:target.toString(),targetAuthor,page.records(),total,page.hasMore(),page.nextAfterId());
    }
    public PageResult<Comment> comments(long viewer,long news,PageQuery q) {
        requireUser(viewer);requireReadable(viewer,news);page(q);
        String filter="c.news_id=? AND c.root_comment_id IS NULL AND "+VISIBLE_COMMENT;
        var rows=jdbc.query("SELECT c.* FROM community_comment c WHERE "+filter+" ORDER BY c.id DESC LIMIT ? OFFSET ?",(rs,i)->comment(viewer,rs),news,q.getPageSize(),q.offset());
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM community_comment c WHERE "+filter,Long.class,news);
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,rows);
    }
    public CommentContext commentContext(long viewer,long news,long targetId) {
        requireUser(viewer);requireReadable(viewer,news);
        var targets=jdbc.query("SELECT c.* FROM community_comment c WHERE c.id=? AND c.news_id=? AND "+VISIBLE_COMMENT,(rs,i)->comment(viewer,rs,false),targetId,news);
        if(targets.isEmpty())throw missing();var target=targets.getFirst();long rootId=Long.parseLong(target.rootCommentId());
        var roots=jdbc.query("SELECT c.* FROM community_comment c WHERE c.id=? AND c.news_id=? AND c.root_comment_id IS NULL AND "+VISIBLE_COMMENT,(rs,i)->comment(viewer,rs),rootId,news);
        if(roots.isEmpty())throw missing();var root=roots.getFirst();
        if(targetId!=rootId && root.replies().stream().noneMatch(reply->reply.id().equals(target.id()))) {
            var replies=new ArrayList<>(root.replies());replies.add(target);replies.sort(Comparator.comparingLong(reply->Long.parseLong(reply.id())));
            root=new Comment(root.id(),root.content(),root.createdAt(),root.author(),root.mine(),root.rootCommentId(),root.replyToCommentId(),root.replyToAuthor(),List.copyOf(replies),root.replyCount(),root.replyHasMore(),root.replyNextAfterId());
        }
        return new CommentContext(target.id(),root);
    }
    private long replyCount(long news,long root) { return jdbc.queryForObject("SELECT COUNT(*) FROM community_comment c WHERE c.news_id=? AND c.root_comment_id=? AND "+VISIBLE_COMMENT,Long.class,news,root); }
    public Replies replies(long viewer,long news,long root,Long after,int size) {
        requireUser(viewer);requireReadable(viewer,news);
        if(size<1 || size>50 || (after!=null && after<1)) throw new BizException(ErrorCode.BAD_REQUEST,"回复分页参数错误");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_comment c WHERE c.id=? AND c.news_id=? AND c.root_comment_id IS NULL AND "+VISIBLE_COMMENT,Long.class,root,news)!=1) throw missing();
        return replyPage(viewer,news,root,after,size);
    }
    private Replies replyPage(long viewer,long news,long root,Long after,int size) {
        List<Object> args=new ArrayList<>(List.of(news,root));
        String cursor=after==null?"":" AND c.id>?";if(after!=null) args.add(after);args.add(size+1);
        var rows=jdbc.query("SELECT c.* FROM community_comment c WHERE c.news_id=? AND c.root_comment_id=? AND "+VISIBLE_COMMENT+cursor+" ORDER BY c.id ASC LIMIT ?",(rs,i)->comment(viewer,rs,false),args.toArray());
        boolean more=rows.size()>size;if(more) rows.removeLast();
        return new Replies(rows,more,rows.isEmpty()?(after==null?null:after.toString()):rows.getLast().id());
    }
    private Long lockReplyTarget(long news,long target) {
        var targets=jdbc.queryForList("SELECT c.root_comment_id FROM community_comment c WHERE c.id=? AND c.news_id=? AND "+VISIBLE_COMMENT,Long.class,target,news);
        if(targets.isEmpty()) throw missing();long root=targets.getFirst()==null?target:targets.getFirst();
        if(jdbc.queryForList("SELECT c.id FROM community_comment c WHERE c.id=? AND c.news_id=? AND c.root_comment_id IS NULL AND "+VISIBLE_COMMENT+" FOR UPDATE",Long.class,root,news).isEmpty()) throw missing();
        if(target!=root && jdbc.queryForList("SELECT c.id FROM community_comment c WHERE c.id=? AND c.news_id=? AND c.root_comment_id=? AND "+VISIBLE_COMMENT+" FOR UPDATE",Long.class,target,news,root).isEmpty()) throw missing();
        return root;
    }
    private static Long number(Object value) { return value==null?null:((Number)value).longValue(); }
    private String text(String content,int max,String key) {
        String result=content==null?"":content.trim();
        if(result.isEmpty() || result.length()>max || key==null || !key.matches("[A-Za-z0-9_-]{8,64}")) throw new BizException(ErrorCode.BAD_REQUEST,"正文或请求标识不合法");
        return result;
    }
    @Transactional
    public Comment addComment(long viewer,long news,CommentRequest request) {
        requireUser(viewer);requireReadable(viewer,news);String content=text(request.content(),500,request.clientRequestId());
        Long target=request.replyToCommentId();if(target!=null && target<1) throw new BizException(ErrorCode.BAD_REQUEST,"回复目标不合法");
        var prior=jdbc.queryForList("SELECT id FROM community_comment WHERE user_id=? AND client_request_id=?",Long.class,viewer,request.clientRequestId());
        if(prior.isEmpty()) {
            Long root=target==null?null:lockReplyTarget(news,target);
            try { jdbc.update("INSERT INTO community_comment(news_id,user_id,content,client_request_id,reply_to_comment_id,root_comment_id) VALUES(?,?,?,?,?,?)",news,viewer,content,request.clientRequestId(),target,root); }
            catch(DuplicateKeyException ex) { /* Read the original row and verify payload below. */ }
        }
        var rows=jdbc.queryForList("SELECT id,news_id,content,is_deleted,reply_to_comment_id FROM community_comment WHERE user_id=? AND client_request_id=? FOR UPDATE",viewer,request.clientRequestId());
        if(rows.isEmpty()) throw new BizException(ErrorCode.INTERNAL_ERROR,"评论保存失败，请重试");
        var row=rows.getFirst();
        if(((Number)row.get("news_id")).longValue()!=news || !content.equals(row.get("content")) || ((Number)row.get("is_deleted")).intValue()!=0 || !Objects.equals(target,number(row.get("reply_to_comment_id")))) throw new BizException(ErrorCode.CONFLICT,"请求已使用，请刷新后重试");
        var saved=jdbc.query("SELECT c.* FROM community_comment c WHERE c.id=? AND c.news_id=? AND "+VISIBLE_COMMENT,(rs,i)->comment(viewer,rs),row.get("id"),news);
        if(saved.isEmpty()) throw missing();
        var recipients=new HashSet<Long>();Long author=articleUser(news);if(author!=null)recipients.add(author);
        if(target!=null)recipients.addAll(jdbc.queryForList("SELECT user_id FROM community_comment WHERE id=? AND news_id=? AND is_deleted=0",Long.class,target,news));
        long commentId=((Number)row.get("id")).longValue();
        for(long recipient:recipients) notifyEvent(recipient,viewer,"COMMENT",commentId,news,commentId);
        return saved.getFirst();
    }
    @Transactional
    public void deleteComment(long viewer,long news,long comment) {
        requireUser(viewer);requireReadable(viewer,news);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_comment WHERE id=? AND news_id=? AND user_id=?",Long.class,comment,news,viewer)==0) throw missing();
        jdbc.update("UPDATE community_comment SET is_deleted=1 WHERE id=? AND news_id=? AND user_id=?",comment,news,viewer);
    }
    private Message message(long viewer,ResultSet rs) throws SQLException {
        return new Message(rs.getString("id"),rs.getString("sender_id"),rs.getString("recipient_id"),rs.getString("content"),time(rs,"created_at"),time(rs,"read_at"),rs.getLong("sender_id")==viewer);
    }
    private LocalDateTime time(ResultSet rs,String name) throws SQLException {
        var value=rs.getTimestamp(name);return value==null?null:value.toLocalDateTime();
    }
    @Transactional
    public Message send(long viewer,long peer,MessageRequest request) {
        requirePeer(viewer,peer);String content=text(request.content(),1000,request.clientRequestId());
        try { jdbc.update("INSERT INTO community_message(sender_id,recipient_id,content,client_request_id) VALUES(?,?,?,?)",viewer,peer,content,request.clientRequestId()); }
        catch(DuplicateKeyException ex) { /* Same client ID is retried without another delivery. */ }
        Message saved=jdbc.queryForObject("SELECT * FROM community_message WHERE sender_id=? AND client_request_id=? FOR UPDATE",(rs,i)->message(viewer,rs),viewer,request.clientRequestId());
        if(saved==null) throw new BizException(ErrorCode.INTERNAL_ERROR,"发送失败，请重试");
        if(!saved.recipientId().equals(Long.toString(peer)) || !saved.content().equals(content)) throw new BizException(ErrorCode.CONFLICT,"发送标识已使用，请重新确认消息");
        return saved;
    }
    private static final String PAIR=" ((sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?)) ";
    public Messages messages(long viewer,long peer,Long before,int size) {
        requirePeer(viewer,peer);
        if(size<1 || size>50 || (before!=null && before<1)) throw new BizException(ErrorCode.BAD_REQUEST,"分页参数错误");
        var args=new ArrayList<Object>(List.of(viewer,peer,peer,viewer));
        String cursor=before==null?"":" AND id<? ";if(before!=null) args.add(before);args.add(size+1);
        var rows=jdbc.query("SELECT * FROM community_message WHERE "+PAIR+cursor+" ORDER BY id DESC LIMIT ?",(rs,i)->message(viewer,rs),args.toArray());
        boolean more=rows.size()>size;if(more) rows.removeLast();Collections.reverse(rows);
        return new Messages(rows,more,rows.isEmpty()?null:rows.getFirst().id());
    }
    @Transactional
    public void read(long viewer,long peer,String through) {
        requirePeer(viewer,peer);long id;
        try { id=Long.parseLong(through);if(id<1) throw new NumberFormatException(); } catch(RuntimeException ex) { throw new BizException(ErrorCode.BAD_REQUEST,"消息标识不合法"); }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_message WHERE id=? AND "+PAIR,Long.class,id,viewer,peer,peer,viewer)!=1) throw missing();
        jdbc.update("UPDATE community_message SET read_at=CURRENT_TIMESTAMP WHERE sender_id=? AND recipient_id=? AND id<=? AND read_at IS NULL",peer,viewer,id);
    }
    public PageResult<Conversation> inbox(long viewer,PageQuery q) {
        requireUser(viewer);page(q);
        String grouped="SELECT CASE WHEN sender_id=? THEN recipient_id ELSE sender_id END peer_id,MAX(id) last_id,SUM(CASE WHEN recipient_id=? AND read_at IS NULL THEN 1 ELSE 0 END) unread_count FROM community_message WHERE sender_id=? OR recipient_id=? GROUP BY peer_id";
        String from=" FROM ("+grouped+") t JOIN user_account u ON u.id=t.peer_id AND "+ACTIVE;
        Object[] args={viewer,viewer,viewer,viewer};
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args);
        var params=new ArrayList<Object>(Arrays.asList(args));params.add(q.getPageSize());params.add(q.offset());
        var rows=jdbc.query("SELECT t.peer_id,t.unread_count,m.*"+from+" JOIN community_message m ON m.id=t.last_id ORDER BY t.last_id DESC LIMIT ? OFFSET ?",
                (rs,i)->new Conversation(profile(viewer,"u"+rs.getLong("peer_id")),message(viewer,rs),rs.getLong("unread_count")),params.toArray());
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,rows);
    }
    public Unread unread(long viewer) {
        requireUser(viewer);
        String filter=" FROM community_message m JOIN user_account u ON u.id=m.sender_id AND u.is_deleted=0 AND u.account_status=1 WHERE m.recipient_id=? AND m.read_at IS NULL";
        return jdbc.queryForObject("SELECT t.unread_count,m.id,m.sender_id FROM (SELECT COUNT(*) unread_count,MAX(m.id) latest_id"+filter+") t LEFT JOIN community_message m ON m.id=t.latest_id",
                (rs,n)->new Unread(rs.getLong(1),rs.getString(2),rs.getString(3)),viewer);
    }
    public List<PublicationDtos.ArticleImage> articleImages(long newsId) {
        return jdbc.query("SELECT i.media_id FROM community_article_image i JOIN media_file m ON m.id=i.media_id WHERE i.news_id=? AND m.biz_type=7 ORDER BY i.sort_no",(rs,n)->new PublicationDtos.ArticleImage(rs.getString(1)),newsId);
    }
    public List<PublicationDtos.ArticleImage> articleVideos(long newsId) {
        return jdbc.query("SELECT i.media_id FROM community_article_image i JOIN media_file m ON m.id=i.media_id WHERE i.news_id=? AND m.biz_type=8 ORDER BY i.sort_no",(rs,n)->new PublicationDtos.ArticleImage(rs.getString(1)),newsId);
    }
    /** 资讯详情标签；无投稿标签时返回空列表。 */
    public List<PublicationDtos.Tag> articleTags(long newsId) {
        return jdbc.query("SELECT t.code,t.name FROM community_article_tag a JOIN community_tag t ON t.id=a.tag_id WHERE a.news_id=? AND t.enabled=1 ORDER BY t.sort_no,t.id",(rs,n)->new PublicationDtos.Tag(rs.getString(1),rs.getString(2)),newsId);
    }
    private Long articleUser(long news) {
        var users=jdbc.queryForList("SELECT b.user_id FROM cms_news n JOIN community_blogger b ON b.id=n.blogger_id JOIN user_account u ON u.id=b.user_id AND "+ACTIVE+" WHERE n.id=? AND n.is_deleted=0",Long.class,news);
        return users.isEmpty()?null:users.getFirst();
    }
    private void notifyEvent(long recipient,long actor,String kind,long target,Long news,Long comment) {
        if(recipient==actor)return;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id IN (?,?) AND is_deleted=0 AND account_status=1",Long.class,recipient,actor)!=2)return;
        jdbc.update("INSERT INTO community_notification(recipient_id,actor_id,kind,target_id,news_id,comment_id) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",recipient,actor,kind,target,news,comment);
    }
    public void notifyAction(long actor,long news,String kind,boolean enabled) {
        if(!Set.of("LIKE","COLLECT").contains(kind))throw new IllegalArgumentException("kind");
        if(!enabled){jdbc.update("DELETE FROM community_notification WHERE actor_id=? AND kind=? AND target_id=?",actor,kind,news);return;}
        Long recipient=articleUser(news);if(recipient!=null)notifyEvent(recipient,actor,kind,news,news,null);
    }
}
