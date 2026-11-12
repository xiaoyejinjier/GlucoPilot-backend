package com.threadfit.glucopilot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.AuthEmailCode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AuthEmailCodeMapper extends BaseMapper<AuthEmailCode> {

    /**
     * 消费一条邮箱验证码：单条条件更新，语义与 {@link AuthSmsCodeMapper#consume} 一致。
     *
     * @return 1 表示消费成功；0 表示码不存在/场景不符/已使用/已过期
     */
    @Update("UPDATE auth_email_code SET is_used = 1 "
            + "WHERE email = #{email} AND scene = #{scene} AND verify_code = #{code} "
            + "AND is_used = 0 AND expire_at > #{now}")
    int consume(@Param("email") String email, @Param("scene") String scene,
                @Param("code") String code, @Param("now") LocalDateTime now);

    /** 账号注销：按邮箱清空验证码（同手机号：验证码表无 user_id，只有邮箱）。 */
    @Delete("DELETE FROM auth_email_code WHERE email = #{email}")
    int purgeByEmail(@Param("email") String email);
}
