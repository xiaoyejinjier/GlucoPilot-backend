package com.threadfit.glucopilot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.UserSession;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    /** 所有登录方式共用账号行锁；即使还没有会话，也能串行签发令牌。 */
    @Select(
            """
            SELECT id
            FROM user_account
            WHERE id = #{userId}
              AND is_deleted = 0
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockAccount(@Param("userId") Long userId);

    /** 仅在旧会话已失效时判断是否存在另一个有效登录，不暴露另一终端的资料。 */
    @Select(
            """
            SELECT COUNT(*)
            FROM user_session
            WHERE user_id = #{userId}
              AND access_token <> #{token}
              AND is_valid = 1
              AND is_deleted = 0
              AND expire_at > #{now}
            """)
    long countOtherActiveSessions(
            @Param("userId") Long userId,
            @Param("token") String token,
            @Param("now") LocalDateTime now);

    /** 账号注销：物理删除该用户全部会话（提交时已先置 is_valid=0，这里只做收尾）。 */
    @Delete("DELETE FROM user_session WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
