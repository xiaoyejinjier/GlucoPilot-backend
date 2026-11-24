package com.threadfit.glucopilot.payment.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.dto.SandboxPaymentDtos.Proof;
import com.threadfit.glucopilot.payment.entity.SandboxRecharge;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.wallet.dto.WalletDtos;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 充值订单与资金处理：创建订单，核验支付凭据，并在同一事务中更新状态、余额和流水。 */
@Service
@RequiredArgsConstructor
public class SandboxRechargeService {
    private static final Logger log = LoggerFactory.getLogger(SandboxRechargeService.class);

    private final SandboxPaymentMapper mapper;
    private final AlipaySandboxProperties properties;

    // 沙箱开关、应用/商家、密钥和回调地址格式通过基本检查后，才开放支付能力。
    public void requireReady() {
        if (!properties.ready()) throw new BizException(ErrorCode.PAYMENT_UNAVAILABLE);
    }

    /** 创建本地充值订单；这里不向支付宝发起网络请求，也不增加钱包余额。 */
    @Transactional
    public SandboxRecharge create(long userId, WalletDtos.RechargeRequest request) {
        requireReady();
        if (!"QR".equals(request.paymentMode()) && !"PAGE".equals(request.paymentMode()))
            throw new BizException(ErrorCode.BAD_REQUEST, "付款方式不正确");
        // 【1. 账号锁】注销中/已注销账号直接拒绝，替代误导性的"请先开通钱包"；
        //              同时确认本人已开通钱包，并串行处理同一用户并发创建订单的请求。
        Integer accountStatus = mapper.accountStatus(userId);
        if (accountStatus != null
                && (accountStatus == 2 || accountStatus == 3 || accountStatus == 4))
            throw new BizException(ErrorCode.BAD_REQUEST, "账号注销中或已注销，无法充值");
        if (mapper.lockActivatedUser(userId) == null)
            throw new BizException(ErrorCode.BAD_REQUEST, "请先开通钱包");
        // 【2. 幂等创建】同一用户+请求编号对应同一订单；重试不得改变金额或付款方式。
        var previous = mapper.byRequest(userId, request.clientRequestId());
        if (previous != null) {
            if (previous.getAmountCents() != request.amountCents()
                    || !Objects.equals(previous.getPaymentMode(), request.paymentMode()))
                throw new BizException(ErrorCode.CONFLICT, "同一充值请求不能修改金额或付款方式");
            return previous;
        }
        // 【3. 固化订单】生成唯一业务单号，保存金额、应用/商家快照以及15分钟有效期。
        var order = new SandboxRecharge();
        order.setOrderNo("SR" + UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setAmountCents(request.amountCents());
        order.setClientRequestId(request.clientRequestId());
        order.setPaymentMode(request.paymentMode());
        order.setSandboxAppId(properties.getAppId());
        order.setSandboxSellerId(properties.getSellerId());
        order.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        mapper.insert(order);
        return mapper.owned(userId, order.getOrderNo());
    }

    // 按订单号和当前用户共同查询，避免通过猜测单号查看或支付其他人的充值订单。
    public SandboxRecharge owned(long userId, String orderNo) {
        var order = mapper.owned(userId, orderNo);
        if (order == null) throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        return order;
    }

    // 后续付款和查单都使用申请时的商家上下文，当前配置变化时阻止混用旧订单。
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
        // 【1. 锁定订单】主动查单和异步回调可能同时到达，先锁同一行，再判断是否已经处理。
        var order = mapper.lockOrder(proof.orderNo());
        if (order == null) throw new BizException(ErrorCode.NOT_FOUND);
        checkMerchant(order);
        // 【1.5 墓碑拦截】账号已注销（status=3）直接拒绝入账，且不 ensureAccount 新建钱包行。
        Integer accountStatus = mapper.accountStatus(order.getUserId());
        if (accountStatus != null && accountStatus == 3) {
            log.warn("已注销账号充值回调被拒绝 orderNo={} userId={}", order.getOrderNo(), order.getUserId());
            throw new BizException(ErrorCode.CONFLICT, "账号已注销，拒绝入账");
        }
        // 【2. 对账】应用、商家、金额和交易号必须匹配；金额使用BigDecimal精确比较。
        if (!Objects.equals(order.getSandboxAppId(), proof.appId())
                || !Objects.equals(order.getSandboxSellerId(), proof.sellerId())
                || proof.amount() == null
                || proof.amount().compareTo(BigDecimal.valueOf(order.getAmountCents(), 2)) != 0
                || proof.tradeNo() == null
                || !proof.tradeNo().matches("[A-Za-z0-9_-]{1,96}"))
            throw new BizException(ErrorCode.BAD_REQUEST, "支付结果与订单不一致");
        // 接口调用成功不等于付款成功，只有这两种交易状态才允许入账。
        boolean success =
                "TRADE_SUCCESS".equals(proof.status()) || "TRADE_FINISHED".equals(proof.status());
        // 【3. 幂等入账】已付款订单只核对交易号后返回，不再次增加余额或插入流水。
        if ("PAID".equals(order.getStatus())) {
            if (!Objects.equals(order.getProviderTradeNo(), proof.tradeNo()))
                throw new BizException(ErrorCode.CONFLICT, "支付交易号不一致");
            return;
        }
        // 已关闭等状态收到迟到的成功通知时进入冲突核对，不能直接重新打开订单并入账。
        if (!"PENDING".equals(order.getStatus())) {
            if (success) throw new BizException(ErrorCode.CONFLICT, "订单已关闭，请核对支付结果");
            return;
        }
        // 明确关闭则更新订单；其余非成功状态保持待支付，不提前增加余额。
        if ("TRADE_CLOSED".equals(proof.status())) {
            mapper.close(order.getOrderNo());
            return;
        }
        if (!success) return;
        // 【4. 锁定余额】多个不同充值订单也可能同时入账，余额行锁保证前后余额计算一致。
        mapper.ensureAccount(order.getUserId());
        long before = mapper.lockBalance(order.getUserId());
        // 使用整数“分”计算资金，并检查整数溢出；流水保存本次入账后的余额快照。
        long after = Math.addExact(before, order.getAmountCents());
        if (mapper.paid(order.getOrderNo(), proof.tradeNo()) != 1)
            throw new BizException(ErrorCode.CONFLICT);
        // 【5. 原子提交】订单状态、钱包余额、唯一充值流水一起提交，任一步失败则整体回滚。
        mapper.credit(order.getUserId(), order.getAmountCents());
        mapper.ledger(order, after, proof.tradeNo());
    }
}
