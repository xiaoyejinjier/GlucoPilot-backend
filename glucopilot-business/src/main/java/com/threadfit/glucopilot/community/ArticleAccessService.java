package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ArticleAccessService {
    private final JdbcTemplate jdbc;
    public ArticleAccessService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Scope(String visibility,List<String> viewerIds) {}
    public record Policy(String visibility,int revision,List<String> viewerIds) {}
    public record Change(@NotBlank String visibility,@Size(max=50) List<String> viewerIds,@Min(0) int revision) {}
    public record Hidden(@NotNull Boolean hidden) {}
    private static BizException missing(){return new BizException(ErrorCode.NOT_FOUND,"文章不存在或不可查看");}
    private void user(long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1",Long.class,id)!=1)throw missing();}

    // Aliases are source-code constants. Viewer is a numeric identity obtained from RequestContext.
    public static String audienceSql(String n,Long viewer) {
        long user=viewer==null?0:viewer;
        String author="(SELECT va.author_user_id FROM community_article va WHERE va.news_id="+n+".id)";
        return " (NOT EXISTS(SELECT 1 FROM community_article_access ac WHERE ac.news_id="+n+".id AND ac.visibility<>'PUBLIC') OR "+author+"="+user+
            " OR EXISTS(SELECT 1 FROM community_article_access ac JOIN community_article_reader ar ON ar.news_id=ac.news_id WHERE ac.news_id="+n+".id AND ac.visibility='FRIENDS' AND ar.user_id="+user+
            " AND EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id="+user+" AND f.target_key=CONCAT('u',"+author+") AND f.following=1)"+
            " AND EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id="+author+" AND f.target_key=CONCAT('u',"+user+") AND f.following=1))) ";
    }
    public static String readableSql(String n,Long viewer) {
        long user=viewer==null?0:viewer;
        return audienceSql(n,viewer)+" AND NOT EXISTS(SELECT 1 FROM community_article_hidden ah WHERE ah.news_id="+n+".id AND ah.user_id="+user+") ";
    }
    public static String publishedSql(String n) {
        return n+".is_deleted=0 AND "+n+".publish_status=1 AND "+n+".publish_at<=CURRENT_TIMESTAMP AND ("+n+".community_managed=0 OR EXISTS(SELECT 1 FROM community_article pa JOIN user_account au ON au.id=pa.author_user_id AND au.is_deleted=0 AND au.account_status=1 WHERE pa.news_id="+n+".id AND pa.state='PUBLISHED'))";
    }
    public void requireReadable(Long viewer,long id) {
        requireSource(viewer,id,true);
    }
    public void requireReportable(long viewer,long id) {requireSource(viewer,id,false);}
    private void requireSource(Long viewer,long id,boolean preferences) {
        if(viewer!=null)user(viewer);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM cms_news n WHERE n.id=? AND "+publishedSql("n")+" AND "+(preferences?readableSql("n",viewer):audienceSql("n",viewer)),Long.class,id)!=1)throw missing();
    }
    public long views(long id){return jdbc.queryForObject("SELECT COUNT(*) FROM community_article_view WHERE news_id=?",Long.class,id);}
    public Policy policy(long id,boolean owner) {
        var rows=jdbc.queryForList("SELECT visibility,revision FROM community_article_access WHERE news_id=?",id);
        if(rows.isEmpty())return new Policy("PUBLIC",0,List.of());
        var row=rows.getFirst();
        return new Policy(row.get("visibility").toString(),((Number)row.get("revision")).intValue(),owner?readers(id):List.of());
    }
    private List<String> readers(long id){return jdbc.queryForList("SELECT user_id FROM community_article_reader WHERE news_id=? ORDER BY user_id",Long.class,id).stream().map(Object::toString).toList();}
    public Scope normalize(long author,String visibility,List<String> raw) {
        String mode=visibility==null?"PUBLIC":visibility;
        if(!Set.of("PUBLIC","PRIVATE","FRIENDS").contains(mode))throw new BizException(ErrorCode.BAD_REQUEST,"请选择有效的可见范围");
        List<String> ids=raw==null?List.of():raw;
        if(ids.size()>50 || new HashSet<>(ids).size()!=ids.size() || ids.stream().anyMatch(id->id==null||!id.matches("[1-9][0-9]{0,18}")))throw new BizException(ErrorCode.BAD_REQUEST,"最多选择50位好友，不能重复");
        if((mode.equals("FRIENDS")&&ids.isEmpty())||(!mode.equals("FRIENDS")&&!ids.isEmpty()))throw new BizException(ErrorCode.BAD_REQUEST,"请为指定好友可见选择好友");
        var sorted=new TreeSet<Long>();
        for(String id:ids){long peer;try{peer=Long.parseLong(id);}catch(NumberFormatException ex){throw new BizException(ErrorCode.BAD_REQUEST,"好友标识不合法");}
            if(peer==author || jdbc.queryForObject("SELECT COUNT(*) FROM user_account u WHERE u.id=? AND u.is_deleted=0 AND u.account_status=1 AND "+mutualSql("u.id",author),Long.class,peer)!=1)throw new BizException(ErrorCode.BAD_REQUEST,"所选用户已不是互相关注的好友，请重新选择");
            sorted.add(peer);
        }
        return new Scope(mode,sorted.stream().map(Object::toString).toList());
    }
    private static String mutualSql(String peer,long user){return "EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id="+user+" AND f.target_key=CONCAT('u',"+peer+") AND f.following=1) AND EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id="+peer+" AND f.target_key=CONCAT('u',"+user+") AND f.following=1)";}
    public PageResult<CommunityDtos.Profile> friends(long viewer,String keyword,PageQuery q) {
        user(viewer);if(q.getPageNum()<1||q.getPageSize()<1||q.getPageSize()>50)throw new BizException(ErrorCode.BAD_REQUEST,"每页1～50条");
        String term=Objects.toString(keyword,"").trim();if(term.length()>32)throw new BizException(ErrorCode.BAD_REQUEST,"昵称最多32字");
        String from=" FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id AND p.is_deleted=0 WHERE u.is_deleted=0 AND u.account_status=1 AND u.id<>? AND "+mutualSql("u.id",viewer)+" AND LOCATE(?,COALESCE(p.nickname,''))>0";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,viewer,term);
        var rows=jdbc.query("SELECT u.id,p.nickname,p.avatar_url,p.bio"+from+" ORDER BY u.id DESC LIMIT ? OFFSET ?",(rs,i)->new CommunityDtos.Profile("u"+rs.getLong("id"),rs.getString("id"),Objects.toString(rs.getString("nickname"),"糖友"),Objects.toString(rs.getString("avatar_url"),""),Objects.toString(rs.getString("bio"),""),true,false),viewer,term,q.getPageSize(),q.offset());
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,rows);
    }
    // Called inside the publication/draft transaction, after locking the owning article.
    public void saveScope(long id,Scope scope) {
        var current=policy(id,true);
        if(current.visibility().equals(scope.visibility())&&current.viewerIds().equals(scope.viewerIds()))return;
        jdbc.update("INSERT INTO community_article_access(news_id,visibility,revision) VALUES(?,?,1) ON DUPLICATE KEY UPDATE visibility=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP",id,scope.visibility(),scope.visibility());
        jdbc.update("DELETE FROM community_article_reader WHERE news_id=?",id);
        for(String reader:scope.viewerIds())jdbc.update("INSERT INTO community_article_reader(news_id,user_id) VALUES(?,?)",id,Long.parseLong(reader));
    }
    @Transactional public Policy change(long viewer,long id,Change request) {
        user(viewer);
        var authors=jdbc.queryForList("SELECT a.author_user_id FROM community_article a JOIN cms_news n ON n.id=a.news_id WHERE a.news_id=? AND n.is_deleted=0 FOR UPDATE",Long.class,id);
        if(authors.isEmpty()||authors.getFirst()!=viewer)throw missing();
        var scope=normalize(viewer,request.visibility(),request.viewerIds());var current=policy(id,true);
        boolean same=current.visibility().equals(scope.visibility())&&current.viewerIds().equals(scope.viewerIds());
        if(request.revision()!=current.revision() && !(same&&request.revision()==current.revision()-1))throw new BizException(ErrorCode.CONFLICT,"可见范围已变化，请刷新后重试");
        saveScope(id,scope);return policy(id,true);
    }
    @Transactional public Map<String,Long> recordView(long viewer,long id) {
        requireReadable(viewer,id);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_article WHERE news_id=? AND author_user_id=?",Long.class,id,viewer)==0)
            jdbc.update("INSERT IGNORE INTO community_article_view(news_id,user_id,view_date) VALUES(?,?,CURRENT_DATE)",id,viewer);
        return Map.of("viewCount",views(id));
    }
    public void hideAfterReport(long viewer,long id){jdbc.update("INSERT IGNORE INTO community_article_hidden(user_id,news_id) VALUES(?,?)",viewer,id);}
    @Transactional public void hidden(long viewer,long id,boolean hidden) {
        user(viewer);
        if(!hidden){jdbc.update("DELETE FROM community_article_hidden WHERE user_id=? AND news_id=?",viewer,id);return;}
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_article_hidden WHERE user_id=? AND news_id=?",Long.class,viewer,id)>0)return;
        requireReadable(viewer,id);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_article WHERE news_id=? AND author_user_id=?",Long.class,id,viewer)>0)throw new BizException(ErrorCode.BAD_REQUEST,"自己的文章请使用可见范围设置");
        hideAfterReport(viewer,id);
    }
}
