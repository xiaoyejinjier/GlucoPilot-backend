package com.threadfit.glucopilot.ai.business.ocr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.threadfit.glucopilot.ai.business.ocr.entity.OcrTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OcrTaskMapper extends BaseMapper<OcrTask> {

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
