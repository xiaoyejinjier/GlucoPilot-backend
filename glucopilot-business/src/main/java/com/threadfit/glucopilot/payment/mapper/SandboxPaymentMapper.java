package com.threadfit.glucopilot.payment.mapper;

import com.threadfit.glucopilot.payment.entity.SandboxRecharge;

import org.apache.ibatis.annotations.*;

@Mapper
public interface SandboxPaymentMapper {
    SandboxRecharge latestPaid(
            @Param("userId") long userId, @Param("app") String app, @Param("seller") String seller);

    Long lockActivatedUser(@Param("userId") long userId);

    SandboxRecharge byRequest(@Param("userId") long userId, @Param("requestId") String requestId);

    SandboxRecharge owned(@Param("userId") long userId, @Param("orderNo") String orderNo);

    SandboxRecharge lockOrder(@Param("orderNo") String orderNo);

    /** 沙箱余额行不存在时返回 null（注销资产校验按 0 处理）。 */
    Long balanceCents(@Param("userId") long userId);

    Long incomeAvailableCents(@Param("userId") long userId);

    /** 未过期（expires_at > NOW）的 PENDING 充值单数量。 */
    long countInFlightRecharges(@Param("userId") long userId);

    /** 账号状态，用户不存在或逻辑删除返回 null。 */
    Integer accountStatus(@Param("userId") long userId);

    int insert(@Param("order") SandboxRecharge order);

    int saveQr(@Param("orderNo") String orderNo, @Param("qr") String qr);

    int claimQuery(@Param("orderNo") String orderNo);

    int paid(@Param("orderNo") String orderNo, @Param("tradeNo") String tradeNo);

    int close(@Param("orderNo") String orderNo);

    void ensureAccount(@Param("userId") long userId);

    long lockBalance(@Param("userId") long userId);

    int credit(@Param("userId") long userId, @Param("amount") int amount);

    int ledger(
            @Param("order") SandboxRecharge order,
            @Param("balance") long balance,
            @Param("tradeNo") String tradeNo);
}
