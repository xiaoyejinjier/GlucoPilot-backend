package com.threadfit.glucopilot.wallet.withdrawal.mapper;

import com.threadfit.glucopilot.wallet.withdrawal.entity.*;

import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.*;

@Mapper
public interface WithdrawalMapper {
    WithdrawalOrder byRequest(@Param("user") long user, @Param("key") String key);

    WithdrawalOrder lock(@Param("no") String no);

    WithdrawalOrder find(@Param("no") String no);

    WithdrawalBalance balance(@Param("user") long user, @Param("lock") boolean lock);

    int insert(
            @Param("no") String no,
            @Param("user") long user,
            @Param("amount") int amount,
            @Param("key") String key,
            @Param("payee") String payee,
            @Param("app") String app,
            @Param("seller") String seller);

    int recipient(
            @Param("no") String no,
            @Param("payee") String payee,
            @Param("app") String app,
            @Param("seller") String seller);

    int claimTransfer(@Param("no") String no);

    int transferResult(
            @Param("no") String no, @Param("state") String state, @Param("code") String code);

    int transferFailed(@Param("no") String no);

    int transferLog(
            @Param("no") String no,
            @Param("action") String action,
            @Param("state") String state,
            @Param("code") String code,
            @Param("actor") Long actor);

    List<String> activeTransfers(@Param("user") long user);

    int freeze(@Param("user") long user, @Param("amount") int amount);

    int release(
            @Param("user") long user, @Param("amount") int amount, @Param("refund") boolean refund);

    int review(
            @Param("no") String no,
            @Param("ops") long ops,
            @Param("status") String status,
            @Param("note") String note,
            @Param("due") LocalDate due,
            @Param("version") long version);

    int complete(@Param("no") String no, @Param("reference") String reference);

    int event(
            @Param("no") String no,
            @Param("type") String type,
            @Param("amount") int amount,
            @Param("balance") WithdrawalBalance balance,
            @Param("actor") Long actor);

    List<WithdrawalOrder> list(
            @Param("user") Long user,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") long offset);

    long count(@Param("user") Long user, @Param("status") String status);

    List<String> due(@Param("today") LocalDate today);

    Integer workingDay(@Param("date") LocalDate date);

    int calendar(
            @Param("date") LocalDate date,
            @Param("working") boolean working,
            @Param("note") String note,
            @Param("ops") long ops);

    List<Map<String, Object>> calendarRows(
            @Param("start") LocalDate start, @Param("end") LocalDate end);
}
