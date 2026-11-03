package com.threadfit.glucopilot.export.mapper;

import com.threadfit.glucopilot.export.entity.DataExportFileRow;
import com.threadfit.glucopilot.export.entity.DataExportTaskRow;

import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 导出持久化：参数均绑定，锁查询禁用缓存并参与Service的Spring事务。 */
@Mapper
public interface DataExportMapper {
    @Delete(
            "DELETE FROM data_export_file WHERE task_id IN"
                    + " (SELECT id FROM data_export_task WHERE user_id=#{user})")
    int purgeFilesByUser(@Param("user") long user);

    @Delete("DELETE FROM data_export_task WHERE user_id=#{user}")
    int purgeTasksByUser(@Param("user") long user);

    String FROM =
            """
            FROM data_export_task t
            JOIN user_account u ON u.id=t.user_id AND u.is_deleted=0
            LEFT JOIN user_profile p ON p.user_id=u.id AND p.is_deleted=0
            LEFT JOIN data_export_file f ON f.task_id=t.id
            """;
    String SELECT =
            """
            SELECT t.id,t.user_id,t.export_scope,t.task_status,t.start_date,t.end_date,
                   t.gmt_create created_at,t.finished_at,t.user_deleted_at,t.error_msg,
                   COALESCE(NULLIF(p.nickname,''),u.username,'用户') user_name,
                   COALESCE(f.glucose_count,0) glucose_count,
                   COALESCE(f.meal_count,0) meal_count
            """
                    + FROM;
    String FILTER =
            """
            WHERE t.is_deleted=0
            <if test="query.viewer != null">
              AND t.user_id=#{query.viewer} AND t.user_deleted_at IS NULL
            </if>
            <if test="query.status != null">AND t.task_status=#{query.status}</if>
            <if test="query.keyword != null">
              AND (LOCATE(#{query.keyword},COALESCE(p.nickname,''))>0
                   OR LOCATE(#{query.keyword},COALESCE(u.username,''))>0)
            </if>
            """;

    @Select(
            "<script>"
                    + SELECT
                    + """
                      WHERE t.id=#{id} AND t.is_deleted=0
                      <if test="viewer != null">
                        AND t.user_id=#{viewer} AND t.user_deleted_at IS NULL
                      </if>
                      </script>
                      """)
    DataExportTaskRow selectTask(@Param("viewer") Long viewer, @Param("id") long id);

    @Select("<script>SELECT COUNT(*) " + FROM + FILTER + "</script>")
    long countTasks(@Param("query") DataExportQuery query);

    @Select(
            "<script>"
                    + SELECT
                    + FILTER
                    + " ORDER BY t.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<DataExportTaskRow> selectTasks(
            @Param("query") DataExportQuery query,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select(
            "SELECT id FROM user_account WHERE id=#{user} AND is_deleted=0 AND account_status=1 FOR"
                    + " UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockActiveUser(@Param("user") long user);

    @Select(
            "SELECT id,user_deleted_at FROM data_export_task WHERE user_id=#{user} AND"
                    + " client_request_id=#{key}")
    DataExportTaskRow selectRequest(@Param("user") long user, @Param("key") String key);

    @Select(
            "SELECT COUNT(*) FROM data_export_task WHERE user_id=#{user} AND is_deleted=0 AND"
                    + " gmt_create>#{since}")
    long countRecentTasks(@Param("user") long user, @Param("since") LocalDateTime since);

    @Insert(
            """
INSERT INTO data_export_task(user_id,export_scope,start_date,end_date,client_request_id,created_by)
VALUES(#{user},3,#{from},#{to},#{key},#{user})
""")
    int insertTask(
            @Param("user") long user,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("key") String key);

    @Select(
            "<script>SELECT id FROM data_export_task WHERE id=#{id} AND is_deleted=0<if"
                + " test='viewer != null'> AND user_id=#{viewer} AND user_deleted_at IS NULL</if>"
                + " FOR UPDATE</script>")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockTask(@Param("viewer") Long viewer, @Param("id") long id);

    // 移除已隐藏任务仍成功：保留原记录，允许用户重复点击删除，管理员继续可见。
    @Select(
            "SELECT id FROM data_export_task WHERE id=#{id} AND user_id=#{user} AND is_deleted=0"
                    + " FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockOwnedTask(@Param("user") long user, @Param("id") long id);

    @Update(
            "UPDATE data_export_task SET user_deleted_at=CURRENT_TIMESTAMP WHERE id=#{id} AND"
                    + " user_id=#{user} AND user_deleted_at IS NULL")
    int hideForUser(@Param("user") long user, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM data_export_file WHERE task_id=#{id}")
    long countFiles(@Param("id") long id);

    @Update(
            "UPDATE data_export_task SET task_status=1,error_msg=NULL,finished_at=NULL WHERE"
                    + " id=#{id}")
    int markProcessing(@Param("id") long id);

    @Select(
            """
            SELECT record_date,node_code,glucose_value,source_type,recorded_at,remark
            FROM glucose_record WHERE user_id=#{user} AND is_deleted=0
              AND record_date>=#{from} AND record_date<=#{to}
            ORDER BY record_date,node_code,id LIMIT 20001
            """)
    List<Map<String, Object>> selectGlucoseRecords(
            @Param("user") long user, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select(
            """
SELECT record_date,node_code,foods_text,carbs_g,hunger_level,supplement_text,recorded_at
FROM meal_record WHERE user_id=#{user} AND is_deleted=0
  AND record_date>=#{from} AND record_date<=#{to}
ORDER BY record_date,node_code,id LIMIT 20001
""")
    List<Map<String, Object>> selectMealRecords(
            @Param("user") long user, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Delete("DELETE FROM data_export_file WHERE task_id=#{id}")
    int deleteFile(@Param("id") long id);

    @Insert(
            "INSERT INTO data_export_file(task_id,file_bytes,glucose_count,meal_count)"
                    + " VALUES(#{id},#{bytes},#{glucoseCount},#{mealCount})")
    int insertFile(
            @Param("id") long id,
            @Param("bytes") byte[] bytes,
            @Param("glucoseCount") int glucoseCount,
            @Param("mealCount") int mealCount);

    @Update(
            "UPDATE data_export_task SET"
                + " task_status=2,file_url=NULL,summary_text=#{summary},finished_at=CURRENT_TIMESTAMP,error_msg=NULL"
                + " WHERE id=#{id}")
    int markDone(@Param("id") long id, @Param("summary") String summary);

    @Update(
            "UPDATE data_export_task SET"
                    + " task_status=3,error_msg=#{message},finished_at=CURRENT_TIMESTAMP WHERE"
                    + " id=#{id}")
    int markFailed(@Param("id") long id, @Param("message") String message);

    @Select("SELECT file_bytes FROM data_export_file WHERE task_id=#{id}")
    DataExportFileRow selectFile(@Param("id") long id);
}
