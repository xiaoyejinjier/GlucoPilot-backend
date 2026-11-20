package com.threadfit.glucopilot.dashboard.mapper;

import com.threadfit.glucopilot.dashboard.dto.DashboardDtos.*;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdminDashboardMapper {
    Counts counts(
            @Param("today") LocalDate today,
            @Param("start") LocalDate start,
            @Param("monthStart") LocalDate monthStart,
            @Param("nextDay") LocalDate nextDay,
            @Param("asOf") LocalDateTime asOf);

    List<DailyRow> daily(@Param("start") LocalDate start, @Param("nextDay") LocalDate nextDay);

    List<ValueRow> compliance(
            @Param("start") LocalDate start,
            @Param("nextDay") LocalDate nextDay,
            @Param("low") BigDecimal low,
            @Param("high") BigDecimal high);

    List<ValueRow> channels(@Param("asOf") LocalDateTime asOf);

    List<ValueRow> nodes(@Param("start") LocalDate start, @Param("nextDay") LocalDate nextDay);
}
