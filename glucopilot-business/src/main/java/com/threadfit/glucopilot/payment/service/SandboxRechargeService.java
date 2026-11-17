package com.threadfit.glucopilot.payment.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.dto.SandboxPaymentDtos.Proof;
import com.threadfit.glucopilot.payment.entity.SandboxRecharge;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.wallet.dto.WalletDtos;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SandboxRechargeService {
    private final SandboxPaymentMapper mapper;
    private final AlipaySandboxProperties properties;

    public void requireReady() {
        if (!properties.ready()) throw new BizException(ErrorCode.PAYMENT_UNAVAILABLE);
    }

    @Transactional
    public SandboxRecharge create(long userId, WalletDtos.RechargeRequest request) {
        requireReady();
        if (mapper.lockActivatedUser(userId) == null)
            throw new BizException(ErrorCode.BAD_REQUEST, "请先开通钱包");
        var previous = mapper.byRequest(userId, request.clientRequestId());
        if (previous != null) {
            if (previous.getAmountCents() != request.amountCents())
                throw new BizException(ErrorCode.CONFLICT, "同一充值请求不能修改金额");
            return previous;
        }
        var order = new SandboxRecharge();
        order.setOrderNo("SR" + UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setAmountCents(request.amountCents());
        order.setClientRequestId(request.clientRequestId());
        order.setSandboxAppId(properties.getAppId());
        order.setSandboxSellerId(properties.getSellerId());
        order.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        mapper.insert(order);
        return mapper.owned(userId, order.getOrderNo());
    }

    public SandboxRecharge owned(long userId, String orderNo) {
        var order = mapper.owned(userId, orderNo);
        if (order == null) throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        return order;
    }

    public void checkMerchant(SandboxRecharge order) {
        requireReady();
        if (!"ALIPAY_SANDBOX".equals(order.getPaymentProvider())
                || !properties.getAppId().equals(order.getSandboxAppId())
                || !properties.getSellerId().equals(order.getSandboxSellerId()))
            throw new BizException(ErrorCode.CONFLICT, "订单的沙箱应用与当前配置不一致");
    }

    /** 订单行锁 + 状态条件 + 流水唯一键：重复回调/查单只入账一次。 */
    @Transactional
    public void settle(Proof proof) {
        var order = mapper.lockOrder(proof.orderNo());
        if (order == null) throw new BizException(ErrorCode.NOT_FOUND);
        checkMerchant(order);
        if (!Objects.equals(order.getSandboxAppId(), proof.appId())
                || !Objects.equals(order.getSandboxSellerId(), proof.sellerId())
                || proof.amount() == null
                || proof.amount().compareTo(BigDecimal.valueOf(order.getAmountCents(), 2)) != 0
                || proof.tradeNo() == null
                || !proof.tradeNo().matches("[A-Za-z0-9_-]{1,96}"))
            throw new BizException(ErrorCode.BAD_REQUEST, "支付结果与订单不一致");
        boolean success =
                "TRADE_SUCCESS".equals(proof.status()) || "TRADE_FINISHED".equals(proof.status());
        if ("PAID".equals(order.getStatus())) {
            if (!Objects.equals(order.getProviderTradeNo(), proof.tradeNo()))
                throw new BizException(ErrorCode.CONFLICT, "支付交易号不一致");
            return;
        }
        if (!"PENDING".equals(order.getStatus())) {
            if (success) throw new BizException(ErrorCode.CONFLICT, "订单已关闭，请核对支付结果");
            return;
        }
        if ("TRADE_CLOSED".equals(proof.status())) {
            mapper.close(order.getOrderNo());
            return;
        }
        if (!success) return;
        mapper.ensureAccount(order.getUserId());
        long before = mapper.lockBalance(order.getUserId());
        long after = Math.addExact(before, order.getAmountCents());
        if (mapper.paid(order.getOrderNo(), proof.tradeNo()) != 1)
            throw new BizException(ErrorCode.CONFLICT);
        mapper.credit(order.getUserId(), order.getAmountCents());
        mapper.ledger(order, after, proof.tradeNo());
    }
}
