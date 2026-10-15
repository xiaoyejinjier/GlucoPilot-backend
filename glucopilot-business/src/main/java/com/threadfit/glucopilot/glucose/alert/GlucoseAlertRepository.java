package com.threadfit.glucopilot.glucose.alert;

import com.threadfit.glucopilot.common.api.PageQuery;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class GlucoseAlertRepository {
    private final JdbcTemplate jdbc;
    private static final String FROM = """
        FROM glucose_record g
        JOIN user_account u ON u.id=g.user_id AND u.is_deleted=0
        LEFT JOIN user_profile p ON p.user_id=g.user_id AND p.is_deleted=0
        LEFT JOIN gp_glucose_alert_case h ON h.record_id=g.id
          AND h.glucose_value=g.glucose_value AND h.recorded_at=g.recorded_at
        """;
    private static final String ACTIVE = """
        g.is_deleted=0 AND g.node_code IN ('before_breakfast','after_breakfast',
          'before_lunch','after_lunch','before_dinner','after_dinner')
        AND g.glucose_value>0 AND g.glucose_value<40
        AND (g.glucose_value<3.9 OR g.glucose_value>=10.0)
        """;
    private static final String SELECT = """
        SELECT g.id,g.user_id,u.username,COALESCE(NULLIF(p.nickname,''),u.username) nickname,u.phone,
          g.record_date,g.node_code,g.glucose_value,g.source_type,g.remark,g.recorded_at,
          COALESCE(h.status,'PENDING') status,h.note,COALESCE(h.version,0) version,h.handled_by,h.handled_at,h.user_reply,h.replied_at
        """;
    private static final RowMapper<GlucoseAlertDtos.Event> EVENT = (rs, index) -> new GlucoseAlertDtos.Event(
        rs.getString("id"), rs.getString("user_id"), rs.getString("username"), rs.getString("nickname"),
        maskPhone(rs.getString("phone")), rs.getDate("record_date").toLocalDate(), rs.getString("node_code"),
        GlucoseAlertPolicy.nodeName(rs.getString("node_code")), rs.getBigDecimal("glucose_value"),
        rs.getInt("source_type"), rs.getString("remark"), time(rs,"recorded_at"),
        GlucoseAlertPolicy.classify(rs.getString("node_code"),rs.getBigDecimal("glucose_value")),
        rs.getString("status"), rs.getString("note"), rs.getLong("version"),
        rs.getString("handled_by"), time(rs,"handled_at"),rs.getString("user_reply"),time(rs,"replied_at"));

    public record Filter(LocalDate start, LocalDate end, String keyword, String alertType, String status) { }
    record Clause(String sql, List<Object> args) { }

    static Clause where(Filter filter) {
        StringBuilder sql = new StringBuilder(" WHERE ").append(ACTIVE).append(" AND g.record_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(filter.start(),filter.end()));
        if (filter.keyword()!=null && !filter.keyword().isBlank()) {
            // 关键词是字面文本，使用 LOCATE 避免 %/_ 通配符和 SQL 拼接。
            sql.append(" AND (LOCATE(?,u.username)>0 OR LOCATE(?,p.nickname)>0)");
            args.add(filter.keyword().trim()); args.add(filter.keyword().trim());
        }
        if (filter.alertType()!=null) sql.append(" AND g.glucose_value ").append("LOW".equals(filter.alertType())?"<3.9":">=10.0");
        if (filter.status()!=null) { sql.append(" AND COALESCE(h.status,'PENDING')=?"); args.add(filter.status()); }
        return new Clause(sql.toString(),args);
    }

    public Map<String,Long> overview(Filter filter) {
        Clause clause=where(filter);
        String sql="""
            SELECT COUNT(*) total,
            COALESCE(SUM(CASE WHEN g.glucose_value<3.9 THEN 1 ELSE 0 END),0) lowCount,
            COALESCE(SUM(CASE WHEN g.glucose_value>=10.0 THEN 1 ELSE 0 END),0) highCount,
            COALESCE(SUM(CASE WHEN COALESCE(h.status,'PENDING')='PENDING' THEN 1 ELSE 0 END),0) pendingCount,
            COALESCE(SUM(CASE WHEN h.status='PROCESSING' THEN 1 ELSE 0 END),0) processingCount,
            COALESCE(SUM(CASE WHEN h.status='RESOLVED' THEN 1 ELSE 0 END),0) resolvedCount,
            COALESCE(SUM(CASE WHEN h.status='CLOSED' THEN 1 ELSE 0 END),0) closedCount
            """+FROM+clause.sql();
        return jdbc.queryForObject(sql,(rs,i)->{
            Map<String,Long> values=new LinkedHashMap<>();
            for(String key:List.of("total","lowCount","highCount","pendingCount","processingCount","resolvedCount","closedCount"))
                values.put(key,rs.getLong(key));
            return values;
        },clause.args().toArray());
    }

    public List<GlucoseAlertDtos.Event> page(Filter filter,PageQuery page) {
        Clause clause=where(filter);
        List<Object> args=new ArrayList<>(clause.args()); args.add(page.getPageSize()); args.add(page.offset());
        return jdbc.query(SELECT+FROM+clause.sql()+" ORDER BY g.recorded_at DESC,g.id DESC LIMIT ? OFFSET ?",EVENT,args.toArray());
    }

    public GlucoseAlertDtos.Event find(long id) {
        return jdbc.query(SELECT+FROM+" WHERE "+ACTIVE+" AND g.id=?",EVENT,id).stream().findFirst().orElse(null);
    }

    /** 与原记录 UPDATE/DELETE 持有相同行锁，确保处理时快照不能被并发替换。 */
    public boolean lockSource(long id) {
        return !jdbc.query("SELECT id FROM glucose_record WHERE id=? AND is_deleted=0 FOR UPDATE",
                (rs,i)->rs.getLong(1),id).isEmpty();
    }

    public int save(GlucoseAlertDtos.Event event, long opsId, String status, String note,
                    String userReply, LocalDateTime repliedAt, LocalDateTime now) {
        if(event.version()==0) return jdbc.update("""
            INSERT INTO gp_glucose_alert_case(record_id,glucose_value,recorded_at,status,note,handled_by,handled_at,version,user_reply,replied_at)
            VALUES(?,?,?,?,?,?,?,1,?,?)
            """,Long.parseLong(event.id()),event.glucoseValue(),event.recordedAt(),status,note,opsId,now,userReply,repliedAt);
        return jdbc.update("""
            UPDATE gp_glucose_alert_case SET status=?,note=?,handled_by=?,handled_at=?,version=version+1,user_reply=?,replied_at=?
            WHERE record_id=? AND glucose_value=? AND recorded_at=? AND version=?
            """,status,note,opsId,now,userReply,repliedAt,Long.parseLong(event.id()),event.glucoseValue(),event.recordedAt(),event.version());
    }

    public void appendLog(GlucoseAlertDtos.Event event,long opsId,String status,String note,String userReply,LocalDateTime now,String requestId) {
        jdbc.update("""
            INSERT INTO gp_glucose_alert_log(record_id,glucose_value,recorded_at,from_status,to_status,note,operator_id,operated_at,request_id,user_reply)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,Long.parseLong(event.id()),event.glucoseValue(),event.recordedAt(),event.status(),status,note,opsId,now,requestId,userReply);
    }

    public List<GlucoseAlertDtos.History> history(long id) {
        return jdbc.query("SELECT * FROM gp_glucose_alert_log WHERE record_id=? ORDER BY id DESC",
            (rs,i)->new GlucoseAlertDtos.History(rs.getString("id"),rs.getBigDecimal("glucose_value"),time(rs,"recorded_at"),
                rs.getString("from_status"),rs.getString("to_status"),rs.getString("note"),rs.getString("operator_id"),
                time(rs,"operated_at"),rs.getString("request_id"),rs.getString("user_reply")),id);
    }

    public com.threadfit.glucopilot.common.api.PageResult<GlucoseAlertDtos.UserReply> userReplies(long userId,PageQuery page) {
        String from="""
            FROM gp_glucose_alert_case h
            JOIN glucose_record g ON g.id=h.record_id AND g.is_deleted=0
            JOIN user_account u ON u.id=g.user_id AND u.is_deleted=0
            WHERE g.user_id=? AND h.user_reply IS NOT NULL AND TRIM(h.user_reply)<>'' AND h.replied_at IS NOT NULL
            """;
        Long total=jdbc.queryForObject("SELECT COUNT(*) "+from,Long.class,userId);
        var rows=jdbc.query("""
            SELECT h.id,h.record_id,g.record_date,g.node_code,h.glucose_value,h.recorded_at,h.user_reply,h.replied_at
            """+from+" ORDER BY h.replied_at DESC,h.id DESC LIMIT ? OFFSET ?",
            (rs,i)->new GlucoseAlertDtos.UserReply(rs.getString("id"),rs.getString("record_id"),rs.getDate("record_date").toLocalDate(),
                rs.getString("node_code"),GlucoseAlertPolicy.nodeName(rs.getString("node_code")),rs.getBigDecimal("glucose_value"),
                time(rs,"recorded_at"),rs.getString("user_reply"),time(rs,"replied_at")),userId,page.getPageSize(),page.offset());
        return com.threadfit.glucopilot.common.api.PageResult.of(page.getPageNum(),page.getPageSize(),total==null?0:total,rows);
    }

    private static LocalDateTime time(ResultSet rs,String column) throws SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toLocalDateTime();
    }
    private static String maskPhone(String phone) {
        if(phone==null || phone.isBlank()) return "";
        return phone.length()<8?"***":phone.substring(0,3)+"****"+phone.substring(phone.length()-4);
    }
}
