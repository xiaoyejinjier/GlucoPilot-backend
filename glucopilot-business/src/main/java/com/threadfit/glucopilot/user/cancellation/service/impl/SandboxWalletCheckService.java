package com.threadfit.glucopilot.user.cancellation.service.impl;

import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.user.cancellation.service.WalletCheckService;
import com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 沙箱钱包资产校验：直查钱包表，不复用带 {@code account_status=1} 的 {@code walletState}，
 * 保证注销中（status=4）的账号也能被正确判定出未结清资产。
 *
 * <p>判定按优先级返回第一条命中：BALANCE → WITHDRAWAL_IN_FLIGHT → INCOME_PENDING →
 * RECHARGE_IN_FLIGHT。余额行不存在按 0 处理，不抛异常。
 */
@Service
@RequiredArgsConstructor
public class SandboxWalletCheckService implements WalletCheckService {

    private final SandboxPaymentMapper paymentMapper;
    private final WithdrawalMapper withdrawalMapper;

    @Override
    @Transactional(readOnly = true)
    public WalletCheckResult check(Long userId) {
        long balance = valueOrZero(paymentMapper.balanceCents(userId));
        long income = valueOrZero(paymentMapper.incomeAvailableCents(userId));

        // 优先级 1：混合资金（余额 > 收入）→ 存在未提现余额
        if (balance > 0 && income < balance) {
            return WalletCheckResult.fail(BALANCE);
        }
        // 优先级 2：在途提现单（冻结金额与建单同事务，已被此覆盖）
        if (withdrawalMapper.countInFlightWithdrawals(userId) > 0) {
            return WalletCheckResult.fail(WITHDRAWAL_IN_FLIGHT);
        }
        // 优先级 3：纯问诊收入（收入 == 余额）→ 引导医生提现
        if (income > 0 && income == balance) {
            return WalletCheckResult.fail(INCOME_PENDING);
        }
        // 优先级 4：未过期充值单（过期 PENDING 不阻断，避免永久无法注销）
        if (paymentMapper.countInFlightRecharges(userId) > 0) {
            return WalletCheckResult.fail(RECHARGE_IN_FLIGHT);
        }
        return WalletCheckResult.ok();
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0 : value;
    }
}
