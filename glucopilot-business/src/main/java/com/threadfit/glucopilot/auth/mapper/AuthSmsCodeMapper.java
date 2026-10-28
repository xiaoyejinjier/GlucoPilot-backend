package com.threadfit.glucopilot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.AuthSmsCode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthSmsCodeMapper extends BaseMapper<AuthSmsCode> {

    /**
     * 账号注销：按手机号清空验证码。
     * <p>
     * 验证码表没有 user_id，唯一归属线索就是手机号；因此注销时必须<b>先读手机号</b>再置空墓碑。
     */
    @Delete("DELETE FROM auth_sms_code WHERE phone = #{phone}")
    int purgeByPhone(@Param("phone") String phone);
}
