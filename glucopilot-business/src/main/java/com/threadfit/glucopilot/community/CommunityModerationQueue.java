package com.threadfit.glucopilot.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CommunityModerationQueue {
    public record Job(long id,int revision,String title,String body,List<String> tags) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommunityPublicationService publication;
    public CommunityModerationQueue(JdbcTemplate jdbc,PlatformTransactionManager transactions,CommunityPublicationService publication) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.publication=publication;
    }
    public Job claim() {
        return tx.execute(status->{
            jdbc.update("UPDATE community_article SET state='AI_ERROR',ai_verdict='ERROR',ai_reason='上次初审中断，请重新初审',updated_at=CURRENT_TIMESTAMP WHERE state='AI_REVIEWING' AND updated_at<?",LocalDateTime.now().minusMinutes(3));
            var rows=jdbc.query("SELECT a.news_id,a.revision,n.title,n.content_body FROM community_article a JOIN cms_news n ON n.id=a.news_id WHERE a.state='AI_PENDING' AND n.community_managed=1 AND n.is_deleted=0 ORDER BY a.news_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    (rs,n)->new Job(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4),List.of()));
            if(rows.isEmpty()) return null;
            Job row=rows.getFirst();
            jdbc.update("UPDATE community_article SET state='AI_REVIEWING',updated_at=CURRENT_TIMESTAMP WHERE news_id=?",row.id());
            return new Job(row.id(),row.revision(),row.title(),row.body(),publication.tags(row.id()).stream().map(PublicationDtos.Tag::name).toList());
        });
    }
    public boolean finish(Job job,String verdict,String reason,String model) {
        if(!List.of("PASS","REJECT","ERROR").contains(verdict)) throw new IllegalArgumentException("Unknown verdict");
        return Boolean.TRUE.equals(tx.execute(status->{
            String next=switch(verdict) {case "PASS"->"PENDING_ADMIN";case "REJECT"->"AI_REJECTED";default->"AI_ERROR";};
            int changed=jdbc.update("UPDATE community_article SET state=?,ai_verdict=?,ai_reason=?,ai_model=?,ai_checked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE news_id=? AND revision=? AND state='AI_REVIEWING'",
                    next,verdict,cut(reason,1000),cut(model,128),job.id(),job.revision());
            if(changed==1) publication.log("ARTICLE",job.id(),job.revision(),"AI",null,verdict,cut(reason,1000));
            return changed==1;
        }));
    }
    private static String cut(String text,int max) { return text==null?"":text.substring(0,Math.min(max,text.length())); }
}
