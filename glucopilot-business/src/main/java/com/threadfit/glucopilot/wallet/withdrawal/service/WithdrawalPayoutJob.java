package com.threadfit.glucopilot.wallet.withdrawal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WithdrawalPayoutJob {
    private final WithdrawalService service;
    private final WithdrawalTransferService transfers;

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void run() {
        for (String no : service.due()) {
            try {
                transfers.payout(no, null);
            } catch (RuntimeException error) {
                log.warn("支付宝沙箱提现待核对，将在下一轮查单：{}", error.getClass().getSimpleName());
            }
        }
    }
}
