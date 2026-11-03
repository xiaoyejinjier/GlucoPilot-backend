package com.threadfit.glucopilot.glucose.mapper;

import com.threadfit.glucopilot.glucose.entity.GlucoseAlertRows;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 保留源记录行锁、测量快照、版本更新和用户回复隔离的异常血糖持久化。 */
@Mapper
public interface GlucoseAlertMapper {
    String FROM =
            """
            FROM glucose_record g
            JOIN user_account u ON u.id=g.user_id AND u.is_deleted=0
            LEFT JOIN user_profile p ON p.user_id=g.user_id AND p.is_deleted=0
            LEFT JOIN gp_glucose_alert_case h ON h.record_id=g.id
              AND h.glucose_value=g.glucose_value AND h.recorded_at=g.recorded_at
            """;
    String ACTIVE =
            """
            g.is_deleted=0 AND g.node_code IN ('before_breakfast','after_breakfast',
              'before_lunch','after_lunch','before_dinner','after_dinner')
            AND g.glucose_value&gt;0 AND g.glucose_value&lt;40
            AND (g.glucose_value&lt;3.9 OR g.glucose_value&gt;=10.0)
            """;
    String SELECT =
            """
SELECT g.id,g.user_id,u.username,COALESCE(NULLIF(p.nickname,''),u.username) nickname,u.phone,
  g.record_date,g.node_code,g.glucose_value,g.source_type,g.remark,g.recorded_at,
  COALESCE(h.status,'PENDING') status,h.note,COALESCE(h.version,0) version,
  h.handled_by,h.handled_at,h.user_reply,h.replied_at
""";
    String FILTER =
            " WHERE "
                    + ACTIVE
                    + """
AND g.record_date BETWEEN #{query.start} AND #{query.end}
<if test="query.keyword != null">
  AND (LOCATE(#{query.keyword},u.username)&gt;0 OR LOCATE(#{query.keyword},p.nickname)&gt;0)
</if>
<if test="query.alertType != null">
  AND g.glucose_value <choose>
    <when test="query.alertType == 'LOW'"> &lt;3.9 </when>
    <otherwise> &gt;=10.0 </otherwise>
  </choose>
</if>
<if test="query.status != null">AND COALESCE(h.status,'PENDING')=#{query.status}</if>
""";
    String USER_REPLIES_FROM =
            """
            FROM gp_glucose_alert_case h
            JOIN glucose_record g ON g.id=h.record_id AND g.is_deleted=0
            JOIN user_account u ON u.id=g.user_id AND u.is_deleted=0
            WHERE g.user_id=#{userId} AND h.user_reply IS NOT NULL AND TRIM(h.user_reply)<>''
              AND h.replied_at IS NOT NULL
            """;

    @Select(
            "<script>"
                    + """
SELECT COUNT(*) total,
  COALESCE(SUM(CASE WHEN g.glucose_value&lt;3.9 THEN 1 ELSE 0 END),0) low_count,
  COALESCE(SUM(CASE WHEN g.glucose_value&gt;=10.0 THEN 1 ELSE 0 END),0) high_count,
  COALESCE(SUM(CASE WHEN COALESCE(h.status,'PENDING')='PENDING' THEN 1 ELSE 0 END),0) pending_count,
  COALESCE(SUM(CASE WHEN h.status='PROCESSING' THEN 1 ELSE 0 END),0) processing_count,
  COALESCE(SUM(CASE WHEN h.status='RESOLVED' THEN 1 ELSE 0 END),0) resolved_count,
  COALESCE(SUM(CASE WHEN h.status='CLOSED' THEN 1 ELSE 0 END),0) closed_count
"""
                    + FROM
                    + FILTER
                    + "</script>")
    GlucoseAlertRows.Overview selectOverview(@Param("query") GlucoseAlertQuery query);

    @Select(
            "<script>"
                    + SELECT
                    + FROM
                    + FILTER
                    + " ORDER BY g.recorded_at DESC,g.id DESC LIMIT #{limit} OFFSET"
                    + " #{offset}</script>")
    List<GlucoseAlertRows.Event> selectPage(
            @Param("query") GlucoseAlertQuery query,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select("<script>" + SELECT + FROM + " WHERE " + ACTIVE + " AND g.id=#{id}</script>")
    GlucoseAlertRows.Event selectEvent(@Param("id") long id);

    @Select("SELECT id FROM glucose_record WHERE id=#{id} AND is_deleted=0 FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockSource(@Param("id") long id);

    @Insert(
            """
INSERT INTO gp_glucose_alert_case
  (record_id,glucose_value,recorded_at,status,note,handled_by,handled_at,version,user_reply,replied_at)
VALUES(#{change.recordId},#{change.glucoseValue},#{change.recordedAt},#{change.toStatus},
  #{change.note},#{change.operatorId},#{change.operatedAt},1,#{change.userReply},#{change.repliedAt})
""")
    int insertCase(@Param("change") GlucoseAlertRows.Change change);

    @Update(
            """
            UPDATE gp_glucose_alert_case SET status=#{change.toStatus},note=#{change.note},
              handled_by=#{change.operatorId},handled_at=#{change.operatedAt},version=version+1,
              user_reply=#{change.userReply},replied_at=#{change.repliedAt}
            WHERE record_id=#{change.recordId} AND glucose_value=#{change.glucoseValue}
              AND recorded_at=#{change.recordedAt} AND version=#{change.version}
            """)
    int updateCase(@Param("change") GlucoseAlertRows.Change change);

    @Insert(
            """
INSERT INTO gp_glucose_alert_log
  (record_id,glucose_value,recorded_at,from_status,to_status,note,operator_id,operated_at,request_id,user_reply)
VALUES(#{change.recordId},#{change.glucoseValue},#{change.recordedAt},#{change.fromStatus},
  #{change.toStatus},#{change.note},#{change.operatorId},#{change.operatedAt},
  #{change.requestId},#{change.userReply})
""")
    int insertHistory(@Param("change") GlucoseAlertRows.Change change);

    @Select("SELECT * FROM gp_glucose_alert_log WHERE record_id=#{id} ORDER BY id DESC")
    List<GlucoseAlertRows.History> selectHistory(@Param("id") long id);

    @Select("SELECT COUNT(*) " + USER_REPLIES_FROM)
    long countUserReplies(@Param("userId") long userId);

    @Select(
            "SELECT h.id,h.record_id,g.record_date,g.node_code,h.glucose_value,h.recorded_at,"
                    + "h.user_reply,h.replied_at "
                    + USER_REPLIES_FROM
                    + " ORDER BY h.replied_at DESC,h.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<GlucoseAlertRows.UserReply> selectUserReplies(
            @Param("userId") long userId, @Param("limit") int limit, @Param("offset") long offset);
}
