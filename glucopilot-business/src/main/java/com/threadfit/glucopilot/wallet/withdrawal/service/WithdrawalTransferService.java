package com.threadfit.glucopilot.wallet.withdrawal.service;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.service.SandboxTransferGateway;
import com.threadfit.glucopilot.wallet.withdrawal.entity.WithdrawalOrder;
import com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class WithdrawalTransferService {
    private final WithdrawalTransferStore store;
    private final WithdrawalMapper mapper;
    private final SandboxTransferGateway gateway;

    public WithdrawalOrder payout(String no, Long actor) {
        return process(no, true, actor);
    }

    public WithdrawalOrder refresh(String no, Long actor) {
        return process(no, false, actor);
    }

    public void refreshOwn(long user) {
        for (String no : mapper.activeTransfers(user)) refresh(no, null);
    }

    private WithdrawalOrder process(String no, boolean allowSend, Long actor) {
        var order = store.claim(no, allowSend, actor);
        if (order == null) return store.find(no);
        try {
            var proof = gateway.query(order);
            if (proof == null && allowSend) {
                gateway.transfer(order);
                proof = gateway.query(order);
            }
            if (proof == null) store.unknown(no, "ORDER_NOT_EXIST", actor);
            else store.result(no, proof, actor);
        } catch (RuntimeException ex) {
            // 超时、拒绝、验签/对账异常均不伪造成功，也不贸然解冻；同号继续查证。
            String code = "TRANSFER_CHECK_FAILED";
            if (ex instanceof BizException business
                    && business.getData() instanceof Map<?, ?> data) {
                Object value =
                        data.containsKey("providerSubCode")
                                ? data.get("providerSubCode")
                                : data.get("providerCode");
                if (value instanceof String text) code = text;
            }
            store.unknown(no, code, actor);
        }
        return store.find(no);
    }
}
