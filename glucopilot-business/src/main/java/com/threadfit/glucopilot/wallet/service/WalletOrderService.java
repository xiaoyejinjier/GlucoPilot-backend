package com.threadfit.glucopilot.wallet.service;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.wallet.dto.WalletDtos;
import com.threadfit.glucopilot.wallet.entity.WalletOrderRow;
import com.threadfit.glucopilot.wallet.mapper.WalletOrderMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletOrderService {
    private final WalletOrderMapper mapper;
    private final AlipaySandboxProperties sandbox;

    public WalletDtos.Summary summary(long userId) {
        var state = mapper.walletState(userId);
        if (state == null) throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        Object value = state.get("activated_at");
        if (value == null) value = state.get("activatedAt");
        java.time.LocalDateTime activatedAt =
                value instanceof java.sql.Timestamp timestamp
                        ? timestamp.toLocalDateTime()
                        : (java.time.LocalDateTime) value;
        Object balance = state.getOrDefault("balance_cents", state.get("balanceCents"));
        Long cents = activatedAt == null ? null : ((Number) balance).longValue();
        boolean enabled = activatedAt != null && sandbox.ready();
        return new WalletDtos.Summary(
                cents,
                "CNY",
                true,
                enabled,
                activatedAt != null,
                100,
                true,
                activatedAt != null,
                activatedAt,
                true);
    }

    @Transactional
    public WalletDtos.Summary activate(long userId) {
        mapper.activate(userId);
        return summary(userId);
    }

    /**
     * 注销终态处理：关闭 PENDING 充值单 + 清零钱包沙箱余额。
     *
     * <p>由 {@code AccountDataPurger} 在墓碑前调用。余额理论值此时已为 0（提交前阻断未结清资产），
     * 此处是防御性兜底；流水、提现单、充值/问诊订单与 {@code consultation_wallet_entry} 按财务审计
     * 需要保留，仅以 user_id 关联墓碑。幂等：重复调用只让已是 0 行的更新再返回 0。
     */
    @Transactional
    public void purgeAccountData(long userId) {
        mapper.closePendingRecharges(userId);
        mapper.clearWalletAccount(userId);
    }

    @Transactional(readOnly = true)
    public PageResult<WalletDtos.Order> list(
            long userId, boolean recharge, WalletDtos.OrderQuery query) {
        int size = Math.min(query.getPageSize(), 50);
        long offset = (long) (query.getPageNum() - 1) * size;
        long total = mapper.count(userId, recharge, query.getStatus());
        var records =
                mapper.list(userId, recharge, query.getStatus(), offset, size).stream()
                        .map(row -> order(row, recharge))
                        .toList();
        return PageResult.of(query.getPageNum(), size, total, records);
    }

    public WalletDtos.Order detail(long userId, boolean recharge, String orderNo) {
        var row = mapper.detail(userId, recharge, orderNo);
        if (row == null) throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        return order(row, recharge);
    }

    private WalletDtos.Order order(WalletOrderRow row, boolean recharge) {
        return new WalletDtos.Order(
                row.getOrderNo(),
                recharge ? "RECHARGE" : "CONSULTATION",
                recharge ? "钱包充值" : "问诊单查看服务",
                row.getStatus(),
                row.getAmountCents(),
                row.getCurrency(),
                row.getCreatedAt(),
                row.getPaidAt(),
                row.getClosedAt(),
                row.getCreditedAt(),
                row.getConsultationId(),
                row.getReportId(),
                row.getDoctorUserId(),
                row.getSettlementStatus(),
                row.getBalanceAfterCents());
    }
}
