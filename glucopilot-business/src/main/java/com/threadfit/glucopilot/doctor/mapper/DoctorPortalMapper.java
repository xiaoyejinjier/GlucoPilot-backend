package com.threadfit.glucopilot.doctor.mapper;

import com.threadfit.glucopilot.doctor.dto.DoctorPortalDtos.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DoctorPortalMapper {
    boolean bindingExists(@Param("ops") long ops);

    Long boundDoctor(@Param("ops") long ops);

    Long opsForDoctor(@Param("user") long user);

    int bind(@Param("user") long user, @Param("ops") long ops, @Param("actor") long actor);

    long accountCount();

    List<Account> accounts(@Param("limit") int limit, @Param("offset") long offset);

    long incomeCount(@Param("user") long user);

    List<IncomeEntry> income(
            @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);
}
