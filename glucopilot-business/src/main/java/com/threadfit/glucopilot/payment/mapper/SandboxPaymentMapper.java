package com.threadfit.glucopilot.payment.mapper;

import com.threadfit.glucopilot.payment.entity.SandboxRecharge;

import org.apache.ibatis.annotations.*;

@Mapper
public interface SandboxPaymentMapper {
    Long lockActivatedUser(@Param("userId") long userId);

    SandboxRecharge byRequest(@Param("userId") long userId, @Param("requestId") String requestId);

    SandboxRecharge owned(@Param("userId") long userId, @Param("orderNo") String orderNo);

    SandboxRecharge lockOrder(@Param("orderNo") String orderNo);

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
