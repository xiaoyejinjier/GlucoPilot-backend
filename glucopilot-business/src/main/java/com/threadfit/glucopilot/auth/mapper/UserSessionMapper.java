package com.threadfit.glucopilot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.UserSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    /** 账号注销：物理删除该用户全部会话（提交时已先置 is_valid=0，这里只做收尾）。 */
    @Delete("DELETE FROM user_session WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
