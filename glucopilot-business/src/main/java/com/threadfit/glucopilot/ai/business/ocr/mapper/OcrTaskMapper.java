package com.threadfit.glucopilot.ai.business.ocr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.threadfit.glucopilot.ai.business.ocr.entity.OcrTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OcrTaskMapper extends BaseMapper<OcrTask> {

    /**
     * 数据库时钟。
     *
     * <p>"多久算卡住"的阈值必须与写入端同一个时钟（{@code gmt_modified} 由 {@code CURRENT_TIMESTAMP} 写），
     * 否则容器里 DB（UTC）与 JVM（+08）差 8 小时，回收逻辑会把正在跑的任务全部判成超时。
     */
    @Select("SELECT CURRENT_TIMESTAMP")
    LocalDateTime selectDbNow();

    /**
     * 争抢一条待识别任务（条件更新：只有 {@code PENDING} 能被抢到）。
     *
     * <p>它就是 OCR 侧的"认领"：MQ 重复投递、补扫与消费者同时发现同一条任务时，
     * 只有第一个 UPDATE 会成功（0 行表示已被别人抢走或已经出结果），后面的人直接放弃。
     */
    @Update("UPDATE ocr_task SET task_status = 1, updated_by = #{userId} "
            + "WHERE id = #{taskId} AND task_status = 0 AND is_deleted = 0")
    int claimTask(@Param("taskId") Long taskId, @Param("userId") Long userId);

    /** 这个任务是不是还在等识别（补扫与消费者共用）。 */
    @Select("SELECT COUNT(*) FROM ocr_task WHERE id = #{taskId} AND task_status = 0 AND is_deleted = 0")
    int countPendingTask(@Param("taskId") Long taskId);

    /**
     * 补扫用：列出待识别且已过宽限的任务。
     *
     * <p>带 {@code user_id} 是因为识别要校验图片归属（{@code findOwnedMedia}）——只靠 taskId
     * 无法确认调用方有权读这张图，补扫出来的任务必须能重建出完整的调用参数。
     */
    @Select("SELECT id AS taskId, user_id AS userId FROM ocr_task WHERE task_status = 0 AND is_deleted = 0 "
            + "AND gmt_create < #{before} ORDER BY id ASC LIMIT #{limit}")
    java.util.List<java.util.Map<String, Object>> selectPendingTasks(
            @Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * 回收卡在「进行中」的任务：第一次打回待识别（自动重试一次）。
     *
     * <p>{@code error_msg} 在这里被借用作「重试过没有」的标记——表上没有 attempt 列，
     * 而这条信息恰好只对运维有意义（用户看到的是状态）。故意不复用 gmt_modified 判断：
     * 回收本身会把它刷新，第二次回收就再也认不出"已经重试过一次"。
     */
    @Update("UPDATE ocr_task SET task_status = 0, error_msg = #{marker}, updated_by = 0 "
            + "WHERE task_status = 1 AND is_deleted = 0 AND gmt_modified < #{before} "
            + "AND (error_msg IS NULL OR error_msg <> #{marker})")
    int requeueStaleRunning(@Param("before") LocalDateTime before, @Param("marker") String marker);

    /** 回收卡在「进行中」的任务：重试过一次仍然卡住，判失败交人工（不再重试，避免无限烧视觉调用）。 */
    @Update("UPDATE ocr_task SET task_status = 3, error_msg = #{message}, finished_at = CURRENT_TIMESTAMP(3), "
            + "updated_by = 0 WHERE task_status = 1 AND is_deleted = 0 AND gmt_modified < #{before} "
            + "AND error_msg = #{marker}")
    int failStaleRunning(@Param("before") LocalDateTime before,
                         @Param("marker") String marker,
                         @Param("message") String message);
    /** 账号注销：物理删除识别任务（含识别出的血糖读数）。 */
    @Delete("DELETE FROM ocr_task WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);

    /**
     * 审计分页（管理端「AI 审计 → 多模态」）。
     *
     * <p>只查 {@code is_deleted = 0}：注销用户的识别记录会被物理删除（见上面的 {@code purgeByUserId}），
     * 逻辑删除在这里只用于运营侧的软清理，不必进审计视图。
     *
     * @param bizType    1 血糖 / 2 餐食，可空
     * @param taskStatus 0 待处理 / 1 进行中 / 2 成功 / 3 失败，可空
     */
    @Select("<script>"
            + "SELECT * FROM ocr_task WHERE is_deleted = 0"
            + "<if test='userId != null'> AND user_id = #{userId}</if>"
            + "<if test='bizType != null'> AND biz_type = #{bizType}</if>"
            + "<if test='taskStatus != null'> AND task_status = #{taskStatus}</if>"
            + " ORDER BY id DESC"
            + "</script>")
    IPage<OcrTask> selectAuditPage(IPage<OcrTask> page,
                                   @Param("userId") Long userId,
                                   @Param("bizType") Integer bizType,
                                   @Param("taskStatus") Integer taskStatus);
}
