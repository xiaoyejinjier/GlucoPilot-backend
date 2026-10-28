package com.threadfit.glucopilot.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.user.entity.Feedback;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {

    /** 账号注销：物理删除该用户提交的反馈（正文可能含健康信息）。 */
    @Delete("DELETE FROM feedback WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
