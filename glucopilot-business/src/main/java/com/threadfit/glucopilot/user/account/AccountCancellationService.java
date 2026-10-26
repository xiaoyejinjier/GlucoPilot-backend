package com.threadfit.glucopilot.user.account;

import com.threadfit.glucopilot.user.dto.CancellationDtos.QueryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.RecoveryRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.RecoveryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitView;

/** 账号注销：查询状态、发送验证码、提交申请、恢复。仅处理本人（userId 由调用方传入）。 */
public interface AccountCancellationService {

    QueryView getStatus(Long userId);

    SendCodeView sendCode(Long userId);

    SubmitView submit(Long userId, SubmitRequest request);

    RecoveryView recover(Long userId, RecoveryRequest request);
}