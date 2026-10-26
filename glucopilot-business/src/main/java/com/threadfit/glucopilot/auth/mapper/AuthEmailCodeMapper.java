package com.threadfit.glucopilot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.AuthEmailCode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface AuthEmailCodeMapper extends BaseMapper<AuthEmailCode> {

    /** 账号注销：按邮箱清空验证码（同手机号：验证码表无 user_id，只有邮箱）。 */
    @Delete("DELETE FROM auth_email_code WHERE email = #{email}")
    int purgeByEmail(@Param("email") String email);
}
