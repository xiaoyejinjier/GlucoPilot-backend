package com.threadfit.glucopilot.wallet.mapper;

import com.threadfit.glucopilot.wallet.entity.WalletOrderRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WalletOrderMapper {
    java.util.Map<String, Object> walletState(@Param("userId") long userId);

    int activate(@Param("userId") long userId);

    long count(
            @Param("userId") long userId,
            @Param("recharge") boolean recharge,
            @Param("status") String status);

    List<WalletOrderRow> list(
            @Param("userId") long userId,
            @Param("recharge") boolean recharge,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    WalletOrderRow detail(
            @Param("userId") long userId,
            @Param("recharge") boolean recharge,
            @Param("orderNo") String orderNo);
}
