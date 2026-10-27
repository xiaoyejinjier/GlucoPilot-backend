package com.threadfit.glucopilot.user.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.user.account.AccountCancellationService;
import com.threadfit.glucopilot.user.dto.CancellationDtos.CaptchaView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.QueryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.RecoveryView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端账号注销：查询状态、发送验证码、提交申请、恢复。 */
@RestController
@RequestMapping("/api/v1/users/me/cancellation")
@RequiredArgsConstructor
public class AccountCancellationController {

    private static final Logger log = LoggerFactory.getLogger(AccountCancellationController.class);

    private final AccountCancellationService service;

    @GetMapping
    public ApiResponse<QueryView> status() {
        long start = System.currentTimeMillis();
        Long userId = RequestContext.requireUserId();
        QueryView result = service.getStatus(userId);
        log.info("requestId={} cancellation.status userId={} out={} elapsed={}ms",
                RequestContext.getRequestId(), userId, result, System.currentTimeMillis() - start);
        return ApiResponse.ok(result);
    }

    @GetMapping("/captcha")
    public ApiResponse<CaptchaView> captcha() {
        long start = System.currentTimeMillis();
        Long userId = RequestContext.requireUserId();
        CaptchaView result = service.captcha(userId);
        log.info("requestId={} cancellation.captcha userId={} out={} elapsed={}ms",
                RequestContext.getRequestId(), userId, result, System.currentTimeMillis() - start);
        return ApiResponse.ok(result);
    }

    @PostMapping("/code")
    public ApiResponse<SendCodeView> sendCode(@Valid @RequestBody SendCodeRequest request) {
        long start = System.currentTimeMillis();
        Long userId = RequestContext.requireUserId();
        SendCodeView result = service.sendCode(userId, request);
        log.info("requestId={} cancellation.sendCode userId={} in={} out={} elapsed={}ms",
                RequestContext.getRequestId(), userId, request, result, System.currentTimeMillis() - start);
        return ApiResponse.ok(result);
    }

    @PostMapping
    public ApiResponse<SubmitView> submit(@Valid @RequestBody SubmitRequest request) {
        long start = System.currentTimeMillis();
        Long userId = RequestContext.requireUserId();
        SubmitView result = service.submit(userId, request);
        log.info("requestId={} cancellation.submit userId={} in={} out={} elapsed={}ms",
                RequestContext.getRequestId(), userId, request, result, System.currentTimeMillis() - start);
        return ApiResponse.ok(result);
    }

    @PostMapping("/recovery")
    public ApiResponse<RecoveryView> recover() {
        long start = System.currentTimeMillis();
        Long userId = RequestContext.requireUserId();
        RecoveryView result = service.recover(userId);
        log.info("requestId={} cancellation.recover userId={} out={} elapsed={}ms",
                RequestContext.getRequestId(), userId, result, System.currentTimeMillis() - start);
        return ApiResponse.ok(result);
    }
}