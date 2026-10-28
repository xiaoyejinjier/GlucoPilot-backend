package com.threadfit.glucopilot.profile.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserHealthMapper extends BaseMapper<UserHealth> {

    /** 账号注销：物理删除健康档案（不保留软删状态）。 */
    @Delete("DELETE FROM user_health WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
