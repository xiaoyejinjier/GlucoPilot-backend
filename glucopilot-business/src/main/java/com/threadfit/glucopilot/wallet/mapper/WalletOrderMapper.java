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

    /** 注销清理：关闭所有 PENDING 充值单（幂等，阻止墓碑后回调入账）。 */
    int closePendingRecharges(@Param("userId") long userId);

    /** 注销清理：钱包沙箱余额三字段清零并 version+1（防御性兜底）。 */
    int clearWalletAccount(@Param("userId") long userId);
}
