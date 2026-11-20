package com.threadfit.glucopilot.dashboard.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class DashboardDtos {
    private DashboardDtos() {}

    public record Stats(
            Users users,
            Records records,
            Content content,
            List<TrendPoint> trend,
            List<Bucket> complianceDist,
            List<Channel> channelDist,
            List<Node> nodeHeat,
            long foodCount,
            LocalDateTime generatedAt,
            LocalDate rangeStart,
            LocalDate rangeEnd) {}

    public record Users(long total, long today, long active) {}

    public record Records(long todayGlucose, long todayMeal, long monthTotal) {}

    public record Content(long newsViews, long newsCount, long feedbackPending) {}

    public record TrendPoint(LocalDate date, long glucose, long meal, long users) {}

    public record Bucket(String level, long value) {}

    public record Channel(String channel, long value) {}

    public record Node(String node, long value) {}

    @Data
    public static class Counts {
        private long usersTotal;
        private long usersToday;
        private long usersActive;
        private long todayGlucose;
        private long todayMeal;
        private long monthTotal;
        private long newsViews;
        private long newsCount;
        private long feedbackPending;
        private long foodCount;
    }

    @Data
    public static class DailyRow {
        private LocalDate recordDate;
        private long glucose;
        private long meal;
        private long users;
    }

    @Data
    public static class ValueRow {
        private String name;
        private long value;
    }
}
