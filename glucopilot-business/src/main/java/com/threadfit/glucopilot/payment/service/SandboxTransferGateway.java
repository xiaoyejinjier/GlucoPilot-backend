package com.threadfit.glucopilot.payment.service;

import com.threadfit.glucopilot.wallet.withdrawal.entity.WithdrawalOrder;

import java.math.BigDecimal;

/** 仅沙箱转账；查询结果来自SDK验签，不接收前端成功凭证。 */
public interface SandboxTransferGateway {
    record Proof(
            String orderNo,
            String providerOrderId,
            String fundOrderId,
            String status,
            BigDecimal amount,
            String receiverUserId,
            String errorCode) {}

    Proof query(WithdrawalOrder order);

    void transfer(WithdrawalOrder order);
}
