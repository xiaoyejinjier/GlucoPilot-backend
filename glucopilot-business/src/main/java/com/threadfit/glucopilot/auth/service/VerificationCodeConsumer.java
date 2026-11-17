package com.threadfit.glucopilot.auth.service;

import com.threadfit.glucopilot.auth.mapper.AuthEmailCodeMapper;
import com.threadfit.glucopilot.auth.mapper.AuthSmsCodeMapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.EmailUtils;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 登录路径的验证码消费。
 *
 * <h2>为什么独立事务</h2>
 *
 * 登录事务如果因为后续步骤（窗口已过、建会话失败、恢复失败）而回滚，验证码的 {@code is_used}
 * 会跟着回到 0，等于把一次性验证码"复活"成 5 分钟内可重复使用。这里用 {@code REQUIRES_NEW}
 * 让消费先独立提交：失败即烧码（fail-closed），用户重新取码即可。
 *
 * <h2>为什么只有登录用</h2>
 *
 * 绑定流程"先验码再判占用"是为了防账号枚举，号码被占用属于常见业务失败；若同样 fail-closed，
 * 每次占用冲突都会白烧一个验证码。因此绑定与注销提交仍走 {@code AuthService.consumeSmsCode}
 * （原事务语义，仅内部改为条件更新）。
 */
@Component
@RequiredArgsConstructor
public class VerificationCodeConsumer {

    private final AuthSmsCodeMapper authSmsCodeMapper;
    private final AuthEmailCodeMapper authEmailCodeMapper;

    /** 登录用短信验证码消费：独立事务提交，后续回滚不影响本次消费。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeForLoginBySms(String phone, String code) {
        if (authSmsCodeMapper.consume(phone, "login", code, LocalDateTime.now()) != 1) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "验证码错误或已失效");
        }
    }

    /** 登录用邮箱验证码消费：独立事务提交，后续回滚不影响本次消费。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeForLoginByEmail(String email, String code) {
        String normalized = EmailUtils.normalize(email);
        if (authEmailCodeMapper.consume(normalized, "login", code, LocalDateTime.now()) != 1) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "验证码错误或已失效");
        }
    }
}
