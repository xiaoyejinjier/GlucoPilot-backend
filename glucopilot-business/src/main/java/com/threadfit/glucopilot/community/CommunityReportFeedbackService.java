package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
@RequiredArgsConstructor
public class CommunityReportFeedbackService {
    private final JdbcTemplate jdbc;

    public record Feedback(String id,String targetType,String targetId,String targetTitle,String reason,String details,
                           String state,String resolution,String resolutionNote,LocalDateTime createdAt,
                           LocalDateTime resolvedAt,boolean feedbackRead) {}
    private void user(long id) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1",Long.class,id)!=1)
            throw missing();
    }
    private static BizException missing(){return new BizException(ErrorCode.NOT_FOUND,"举报记录不存在");}
    private static LocalDateTime time(ResultSet rs,String name)throws SQLException {
        var t=rs.getTimestamp(name);return t==null?null:t.toLocalDateTime();
    }
    private static Feedback map(ResultSet rs,int row)throws SQLException {
        return new Feedback(rs.getString("id"),rs.getString("target_type"),rs.getString("target_id"),
                rs.getString("target_title"),rs.getString("reason"),rs.getString("details"),rs.getString("state"),
                rs.getString("resolution"),rs.getString("resolution_note"),time(rs,"created_at"),time(rs,"resolved_at"),rs.getTimestamp("feedback_read_at")!=null);
    }
    public PageResult<Feedback> mine(long viewer,PageQuery q) {
        user(viewer);
        if(q.getPageNum()<1||q.getPageSize()<1||q.getPageSize()>50)throw new BizException(ErrorCode.BAD_REQUEST,"每页1～50条");
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM community_report WHERE reporter_id=?",Long.class,viewer);
        var rows=jdbc.query("SELECT * FROM community_report WHERE reporter_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                CommunityReportFeedbackService::map,viewer,q.getPageSize(),q.offset());
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,rows);
    }
    public Feedback get(long viewer,long id) {
        user(viewer);
        var rows=jdbc.query("SELECT * FROM community_report WHERE id=? AND reporter_id=?",CommunityReportFeedbackService::map,id,viewer);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    @Transactional
    public void read(long viewer,long id) {
        get(viewer,id);
        jdbc.update("UPDATE community_report SET feedback_read_at=CURRENT_TIMESTAMP WHERE id=? AND reporter_id=? AND state IN ('RESOLVED','DISMISSED') AND resolved_at IS NOT NULL AND feedback_read_at IS NULL",id,viewer);
    }
}
