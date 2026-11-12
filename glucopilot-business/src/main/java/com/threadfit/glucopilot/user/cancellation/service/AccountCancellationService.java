package com.threadfit.glucopilot.user.cancellation.service;

import com.threadfit.glucopilot.user.dto.CancellationDtos.CaptchaView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.QueryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitView;

/**
 * 账号注销：查询状态、发送验证码、提交申请。
 *
 * <p>恢复没有独立接口：冷静期内正常登录即自动终止注销（见
 * {@link com.threadfit.glucopilot.user.cancellation.service.AccountCancellationRecovery}）。
 */
public interface AccountCancellationService {

    QueryView getStatus(Long userId);

    /** 取一次性滑动验证票据（绑定 userId，180 秒有效）。 */
    CaptchaView captcha(Long userId);

    /** 发码：必须先消费滑动验证票据，通道由前端显式指定（1短信 2邮箱）。 */
    SendCodeView sendCode(Long userId, SendCodeRequest request);

    /** 提交注销：仅支持七天可恢复模式（mode=2）。 */
    SubmitView submit(Long userId, SubmitRequest request);
}
