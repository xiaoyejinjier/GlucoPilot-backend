package com.threadfit.glucopilot.wallet.withdrawal.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.payment.service.SandboxGateway;
import com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

/** 只读取本人历史收款账号或验签交易的付款人；不改变余额和提现订单。 */
@Service
@RequiredArgsConstructor
public class WithdrawalRecipientService {
    private final WithdrawalMapper withdrawals;
    private final SandboxPaymentMapper payments;
    private final ConsultationMapper consultations;
    private final SandboxGateway gateway;
    private final AlipaySandboxProperties sandbox;

    public record DefaultRecipient(String payeeUserId, String source) {}

    // 网络查单不放入数据库事务，避免等待支付宝时占用资金行锁。
    public DefaultRecipient resolve(long user) {
        var doctor = consultations.doctor(user);
        if (doctor == null || !"APPROVED".equals(doctor.getStatus()))
            throw new BizException(ErrorCode.FORBIDDEN, "仅审核通过的医生可使用提现收款账号");
        if (!sandbox.transferReady()) return new DefaultRecipient(null, "NONE");
        String previous = withdrawals.latestPayee(user, sandbox.getAppId(), sandbox.getSellerId());
        if (valid(previous)) return new DefaultRecipient(previous, "HISTORY");
        var order = payments.latestPaid(user, sandbox.getAppId(), sandbox.getSellerId());
        if (order == null) return new DefaultRecipient(null, "NONE");
        if (!Objects.equals(order.getUserId(), user)
                || !"PAID".equals(order.getStatus())
                || !"ALIPAY_SANDBOX".equals(order.getPaymentProvider())
                || order.getProviderTradeNo() == null
                || !order.getProviderTradeNo().matches("[A-Za-z0-9_-]{1,96}")
                || !Objects.equals(order.getSandboxAppId(), sandbox.getAppId())
                || !Objects.equals(order.getSandboxSellerId(), sandbox.getSellerId()))
            throw new BizException(ErrorCode.CONFLICT, "充值订单归属或沙箱应用不一致");
        var proof = gateway.query(order);
        if (proof == null
                || !Objects.equals(proof.orderNo(), order.getOrderNo())
                || !Objects.equals(proof.tradeNo(), order.getProviderTradeNo())
                || !Objects.equals(proof.appId(), order.getSandboxAppId())
                || !Objects.equals(proof.sellerId(), order.getSandboxSellerId())
                || proof.amount() == null
                || proof.amount().compareTo(BigDecimal.valueOf(order.getAmountCents(), 2)) != 0
                || !("TRADE_SUCCESS".equals(proof.status())
                        || "TRADE_FINISHED".equals(proof.status())))
            throw new BizException(ErrorCode.CONFLICT, "付款账号暂未核实，请稍后重试或手动设置");
        return valid(proof.buyerUserId())
                ? new DefaultRecipient(proof.buyerUserId(), "RECHARGE")
                : new DefaultRecipient(null, "NONE");
    }

    private boolean valid(String value) {
        return value != null
                && value.matches("2088[0-9]{12,28}")
                && !value.equals(sandbox.getSellerId());
    }
}
