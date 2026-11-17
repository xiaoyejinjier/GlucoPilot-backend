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

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void run() {
        for (String no : service.due()) {
            try {
                service.complete(no, null);
            } catch (RuntimeException error) {
                log.warn("沙箱提现发放未完成，将在下一轮重试：{}", error.getClass().getSimpleName());
            }
        }
    }
}
