package com.threadfit.glucopilot.community;
import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class CommunityNotificationService {
    private final JdbcTemplate jdbc;
    private final CommunityService community;
    public record Notice(String id,String kind,CommunityDtos.Profile actor,String newsId,String newsTitle,String commentId,String content,LocalDateTime createdAt,boolean read) {}
    public record Summary(long messages,long likes,long collections,long comments,long follows,long reports,long total,String latestMessageId,String latestSenderId,String latestNotificationId,String latestReportId) {}
    public record Read(String kind,String throughId) {}
    private static final Set<String> KINDS=Set.of("LIKE","COLLECT","COMMENT","FOLLOW");
    private static final String FROM=" FROM community_notification x JOIN user_account u ON u.id=x.actor_id AND u.is_deleted=0 AND u.account_status=1 LEFT JOIN cms_news n ON n.id=x.news_id LEFT JOIN community_article a ON a.news_id=n.id LEFT JOIN community_comment c ON c.id=x.comment_id WHERE x.recipient_id=? AND (x.kind='FOLLOW' OR (n.is_deleted=0 AND n.publish_status=1 AND n.publish_at<=CURRENT_TIMESTAMP AND (n.community_managed=0 OR a.state='PUBLISHED'))) AND (x.kind<>'COMMENT' OR (c.is_deleted=0 AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM community_comment root JOIN user_account ru ON ru.id=root.user_id AND ru.is_deleted=0 AND ru.account_status=1 WHERE root.id=c.root_comment_id AND root.is_deleted=0))))";
    private String from(long viewer){return FROM+" AND (x.kind='FOLLOW' OR ("+ArticleAccessService.readableSql("n",viewer)+"))";}
    private void user(long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1",Long.class,id)!=1)throw new BizException(ErrorCode.NOT_FOUND,"用户不存在");}
    private void kind(String kind){if(!KINDS.contains(Objects.toString(kind,"")))throw new BizException(ErrorCode.BAD_REQUEST,"提醒类别不正确");}
    public Summary summary(long viewer) {
        user(viewer);var direct=community.unread(viewer);
        var rows=jdbc.queryForList("SELECT x.kind,COUNT(*) total,MAX(x.id) latest"+from(viewer)+" AND x.read_at IS NULL GROUP BY x.kind",viewer);
        Map<String,Long> counts=new HashMap<>();long latest=0;
        for(var r:rows){counts.put(r.get("kind").toString(),((Number)r.get("total")).longValue());latest=Math.max(latest,((Number)r.get("latest")).longValue());}
        long likes=counts.getOrDefault("LIKE",0L),collections=counts.getOrDefault("COLLECT",0L),comments=counts.getOrDefault("COMMENT",0L),follows=counts.getOrDefault("FOLLOW",0L);
        var feedback=jdbc.queryForMap("SELECT COUNT(*) total,MAX(id) latest FROM community_report WHERE reporter_id=? AND state IN ('RESOLVED','DISMISSED') AND resolved_at IS NOT NULL AND feedback_read_at IS NULL",viewer);
        long reports=((Number)feedback.get("total")).longValue();
        return new Summary(direct.unreadCount(),likes,collections,comments,follows,reports,direct.unreadCount()+likes+collections+comments+follows+reports,
                direct.latestMessageId(),direct.latestSenderId(),latest==0?null:Long.toString(latest),feedback.get("latest")==null?null:feedback.get("latest").toString());
    }
    public PageResult<Notice> list(long viewer,String kind,PageQuery q){user(viewer);kind(kind);if(q.getPageNum()<1||q.getPageSize()<1||q.getPageSize()>50)throw new BizException(ErrorCode.BAD_REQUEST,"每页1～50条");String from=from(viewer)+" AND x.kind=?";long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,viewer,kind);var rows=jdbc.query("SELECT x.*,n.title news_title,c.content comment_content"+from+" ORDER BY x.id DESC LIMIT ? OFFSET ?",(rs,n)->new Notice(rs.getString("id"),rs.getString("kind"),community.profile(viewer,"u"+rs.getLong("actor_id")),rs.getString("news_id"),rs.getString("news_title"),rs.getString("comment_id"),Objects.toString(rs.getString("comment_content"),""),rs.getTimestamp("created_at").toLocalDateTime(),rs.getTimestamp("read_at")!=null),viewer,kind,q.getPageSize(),q.offset());return PageResult.of(q.getPageNum(),q.getPageSize(),total,rows);}
    @Transactional public void read(long viewer,Read p){user(viewer);if(p==null)throw new BizException(ErrorCode.BAD_REQUEST,"请指定提醒范围");kind(p.kind());long through;try{through=Long.parseLong(p.throughId());if(through<1)throw new NumberFormatException();}catch(RuntimeException ex){throw new BizException(ErrorCode.BAD_REQUEST,"提醒标识不正确");}if(jdbc.queryForObject("SELECT COUNT(*) FROM community_notification WHERE id=? AND recipient_id=? AND kind=?",Long.class,through,viewer,p.kind())!=1)throw new BizException(ErrorCode.NOT_FOUND,"提醒不存在");jdbc.update("UPDATE community_notification SET read_at=CURRENT_TIMESTAMP WHERE recipient_id=? AND kind=? AND id<=? AND read_at IS NULL",viewer,p.kind(),through);}
}
