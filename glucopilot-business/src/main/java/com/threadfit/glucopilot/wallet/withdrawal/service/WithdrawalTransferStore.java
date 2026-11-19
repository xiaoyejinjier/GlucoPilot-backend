package com.threadfit.glucopilot.wallet.withdrawal.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.service.SandboxTransferGateway.Proof;
import com.threadfit.glucopilot.wallet.withdrawal.entity.WithdrawalOrder;
import com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** 短事务只处理租约和账务；调用支付宝期间不持有数据库锁。 */
@Service
@RequiredArgsConstructor
public class WithdrawalTransferStore {
    private final WithdrawalMapper mapper;
    private final AlipaySandboxProperties properties;

    public WithdrawalOrder find(String no) {
        var order = mapper.find(no);
        if (order == null) throw new BizException(ErrorCode.NOT_FOUND);
        return order;
    }

    private void merchant(WithdrawalOrder order) {
        if (!properties.transferReady()
                || !Objects.equals(properties.getAppId(), order.getSandboxAppId())
                || !Objects.equals(properties.getSellerId(), order.getSandboxSellerId()))
            throw new BizException(ErrorCode.CONFLICT, "沙箱商家配置已变化，请核对后再出款");
    }

    @Transactional
    public WithdrawalOrder claim(String no, boolean allowSend, Long actor) {
        var order = mapper.lock(no);
        if (order == null) throw new BizException(ErrorCode.NOT_FOUND);
        if (!"APPROVED".equals(order.getStatus())) return null;
        if (!"ALIPAY_SANDBOX_TRANSFER".equals(order.getProvider())
                || order.getPayeeUserId() == null)
            throw new BizException(ErrorCode.BAD_REQUEST, "请先由本人补充支付宝沙箱收款UID");
        merchant(order);
        if (allowSend
                && (order.getExpectedPayoutDate() == null
                        || order.getExpectedPayoutDate()
                                .isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))))
            throw new BizException(ErrorCode.BAD_REQUEST, "尚未到达审核后的预计发放日期");
        if (!allowSend && order.getTransferStartedAt() == null) return null;
        if (mapper.claimTransfer(no) != 1) return null;
        mapper.transferLog(no, "CLAIM", "PROCESSING", null, actor);
        return mapper.find(no);
    }

    @Transactional
    public void result(String no, Proof proof, Long actor) {
        var order = mapper.lock(no);
        if (order == null || !"APPROVED".equals(order.getStatus())) return;
        merchant(order);
        if (!Objects.equals(no, proof.orderNo())
                || (proof.receiverUserId() != null
                        && !proof.receiverUserId().isBlank()
                        && !Objects.equals(order.getPayeeUserId(), proof.receiverUserId())))
            throw new BizException(ErrorCode.CONFLICT, "沙箱转账查询单号或收款人不匹配");
        String state = proof.status();
        if ("SUCCESS".equals(state)) {
            if (proof.amount() == null
                    || proof.amount().compareTo(BigDecimal.valueOf(order.getAmountCents(), 2)) != 0
                    || proof.providerOrderId() == null
                    || !proof.providerOrderId().matches("[A-Za-z0-9_-]{1,64}"))
                throw new BizException(ErrorCode.CONFLICT, "沙箱转账金额或流水不匹配");
            var balance = mapper.balance(order.getUserId(), true);
            if (balance == null || balance.getFrozenCents() < order.getAmountCents())
                throw new BizException(ErrorCode.CONFLICT, "提现冻结金额不匹配");
            if (mapper.release(order.getUserId(), order.getAmountCents(), false) != 1
                    || mapper.transferResult(no, "SUCCESS", null) != 1
                    || mapper.complete(no, proof.providerOrderId()) != 1)
                throw new BizException(ErrorCode.CONFLICT);
            mapper.event(
                    no,
                    "PAYOUT",
                    order.getAmountCents(),
                    mapper.balance(order.getUserId(), true),
                    actor);
            mapper.transferLog(no, "RESULT", "SUCCESS", null, actor);
        } else if ("FAIL".equals(state) || "CLOSED".equals(state)) {
            if (mapper.release(order.getUserId(), order.getAmountCents(), true) != 1
                    || mapper.transferResult(no, "FAILED", safe(proof.errorCode())) != 1
                    || mapper.transferFailed(no) != 1) throw new BizException(ErrorCode.CONFLICT);
            mapper.event(
                    no,
                    "REJECT",
                    order.getAmountCents(),
                    mapper.balance(order.getUserId(), true),
                    actor);
            mapper.transferLog(no, "RESULT", "FAILED", safe(proof.errorCode()), actor);
        } else {
            String next =
                    "DEALING".equals(state) || "WAIT_PAY".equals(state) ? "WAITING" : "UNKNOWN";
            mapper.transferResult(
                    no, next, "UNKNOWN".equals(next) ? "PROVIDER_STATUS_UNKNOWN" : null);
            mapper.transferLog(no, "RESULT", next, null, actor);
        }
    }

    private String safe(String code) {
        return code != null && code.matches("[A-Za-z0-9_.-]{1,64}")
                ? code
                : "TRANSFER_CHECK_FAILED";
    }

    @Transactional
    public void unknown(String no, String code, Long actor) {
        var order = mapper.lock(no);
        if (order == null || !"APPROVED".equals(order.getStatus())) return;
        mapper.transferResult(no, "UNKNOWN", safe(code));
        mapper.transferLog(no, "RESULT", "UNKNOWN", safe(code), actor);
    }
}
