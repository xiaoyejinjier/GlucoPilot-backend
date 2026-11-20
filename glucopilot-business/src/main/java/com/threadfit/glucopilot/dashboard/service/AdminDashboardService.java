package com.threadfit.glucopilot.dashboard.service;

import com.threadfit.glucopilot.dashboard.dto.DashboardDtos;
import com.threadfit.glucopilot.dashboard.mapper.AdminDashboardMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {
    private final AdminDashboardMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardDtos.Stats summary() {
        return summary(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    DashboardDtos.Stats summary(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate start = today.minusDays(13);
        LocalDate nextDay = today.plusDays(1);
        var counts = mapper.counts(today, start, today.withDayOfMonth(1), nextDay, now);
        var byDate = new HashMap<LocalDate, DashboardDtos.DailyRow>();
        for (var row : mapper.daily(start, nextDay)) byDate.put(row.getRecordDate(), row);
        var trend = new ArrayList<DashboardDtos.TrendPoint>();
        for (int i = 0; i < 14; i++) {
            LocalDate date = start.plusDays(i);
            var row = byDate.get(date);
            trend.add(
                    new DashboardDtos.TrendPoint(
                            date,
                            row == null ? 0 : row.getGlucose(),
                            row == null ? 0 : row.getMeal(),
                            row == null ? 0 : row.getUsers()));
        }
        // 与用户管理中的实测达标率口径一致，预测值不参与。
        var buckets = new HashMap<String, Long>();
        for (var row :
                mapper.compliance(start, nextDay, new BigDecimal("3.9"), new BigDecimal("7.8")))
            buckets.put(row.getName(), row.getValue());
        var compliance =
                List.of("达标率≥70%", "达标率40%～<70%", "达标率<40%").stream()
                        .map(name -> new DashboardDtos.Bucket(name, buckets.getOrDefault(name, 0L)))
                        .toList();
        return new DashboardDtos.Stats(
                new DashboardDtos.Users(
                        counts.getUsersTotal(), counts.getUsersToday(), counts.getUsersActive()),
                new DashboardDtos.Records(
                        counts.getTodayGlucose(), counts.getTodayMeal(), counts.getMonthTotal()),
                new DashboardDtos.Content(
                        counts.getNewsViews(), counts.getNewsCount(), counts.getFeedbackPending()),
                trend,
                compliance,
                mapper.channels(now).stream()
                        .map(row -> new DashboardDtos.Channel(row.getName(), row.getValue()))
                        .toList(),
                mapper.nodes(start, nextDay).stream()
                        .map(row -> new DashboardDtos.Node(row.getName(), row.getValue()))
                        .toList(),
                counts.getFoodCount(),
                now,
                start,
                today);
    }
}
