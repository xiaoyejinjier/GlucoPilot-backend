package com.threadfit.glucopilot.wallet.withdrawal.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalRecipientService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WithdrawalRecipientController {
    private final WithdrawalRecipientService service;

    @GetMapping("/api/v1/wallet/withdrawals/recipient")
    public ResponseEntity<ApiResponse<WithdrawalRecipientService.DefaultRecipient>> recipient() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.resolve(RequestContext.requireUserId())));
    }
}
