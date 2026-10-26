package com.threadfit.glucopilot.ai.business.ocr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.business.ocr.entity.OcrTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OcrTaskMapper extends BaseMapper<OcrTask> {

    /** 账号注销：物理删除识别任务（含识别出的血糖读数）。 */
    @Delete("DELETE FROM ocr_task WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
