package com.threadfit.glucopilot.user.cancellation.service.impl;

import com.threadfit.glucopilot.user.cancellation.service.WalletCheckService;

import org.springframework.stereotype.Service;

/**
 * 钱包校验占位实现：恒通过。
 *
 * <p>资产规则（余额/未结算订单/退款中）确定后，替换为真实实现即可，submit 的调用点不用改。
 */
@Service
public class NoopWalletCheckService implements WalletCheckService {

    @Override
    public WalletCheckResult check(Long userId) {
        return WalletCheckResult.ok();
    }
}
