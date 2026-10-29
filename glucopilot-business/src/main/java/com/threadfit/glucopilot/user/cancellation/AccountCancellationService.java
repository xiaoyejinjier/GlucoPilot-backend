package com.threadfit.glucopilot.user.cancellation;

import com.threadfit.glucopilot.user.dto.CancellationDtos.CaptchaView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.QueryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.RecoveryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitView;

/** 账号注销：查询状态、发送验证码、提交申请、恢复。仅处理本人（userId 由调用方传入）。 */
public interface AccountCancellationService {

    QueryView getStatus(Long userId);

    /** 取一次性滑动验证票据（绑定 userId，180 秒有效）。 */
    CaptchaView captcha(Long userId);

    /** 发码：必须先消费滑动验证票据，通道由前端显式指定（1短信 2邮箱）。 */
    SendCodeView sendCode(Long userId, SendCodeRequest request);

    SubmitView submit(Long userId, SubmitRequest request);

    /** 登录即取消注销：会话由 wx.login/验证码登录建立，身份已核验，不再二次验证。 */
    RecoveryView recover(Long userId);
}