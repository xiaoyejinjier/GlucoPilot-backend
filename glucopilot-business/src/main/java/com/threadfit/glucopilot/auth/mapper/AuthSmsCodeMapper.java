package com.threadfit.glucopilot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.AuthSmsCode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AuthSmsCodeMapper extends BaseMapper<AuthSmsCode> {

    /**
     * 消费一条短信验证码：单条条件更新，天然保证"一次性"。
     *
     * <p>用条件更新而不是"select + updateById"：并发两次提交同一个码时只有一次能影响 1 行。
     * 截止时间由调用方传入应用侧时间（不用 {@code NOW(3)}），避免 JVM 与数据库会话时区不一致。
     *
     * @return 1 表示消费成功；0 表示码不存在/场景不符/已使用/已过期
     */
    @Update("UPDATE auth_sms_code SET is_used = 1 "
            + "WHERE phone = #{phone} AND scene = #{scene} AND verify_code = #{code} "
            + "AND is_used = 0 AND expire_at > #{now}")
    int consume(@Param("phone") String phone, @Param("scene") String scene,
                @Param("code") String code, @Param("now") LocalDateTime now);

    /**
     * 账号注销：按手机号清空验证码。
     * <p>
     * 验证码表没有 user_id，唯一归属线索就是手机号；因此注销时必须<b>先读手机号</b>再置空墓碑。
     */
    @Delete("DELETE FROM auth_sms_code WHERE phone = #{phone}")
    int purgeByPhone(@Param("phone") String phone);
}
