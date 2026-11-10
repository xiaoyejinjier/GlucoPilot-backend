package com.threadfit.glucopilot.notification.service;

import com.threadfit.glucopilot.notification.entity.WorkReminderCount;
import com.threadfit.glucopilot.notification.mapper.AdminWorkReminderMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** Read-only work queue summary; permissions are the current operator's server-side grants. */
@Service
@RequiredArgsConstructor
public class AdminWorkReminderService {
    private final AdminWorkReminderMapper mapper;

    public record Item(String type, String title, long count, LocalDateTime latestAt) {}

    public record Summary(long total, List<Item> items) {}

    public Summary summary(Set<String> permissions) {
        var items = new ArrayList<Item>();
        if (allowed(permissions, "content:news:list")) {
            add(items, "REPORT", "新举报", mapper.countReports());
        }
        if (allowed(permissions, "feedback:manage")) {
            add(items, "FEEDBACK", "新反馈", mapper.countFeedback());
        }
        if (allowed(permissions, "food:library:manage")) {
            add(items, "FOOD_REVIEW", "待审核食品", mapper.countPendingFoods());
        }
        items.sort(
                Comparator.comparing(
                        Item::latestAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Summary(items.stream().mapToLong(Item::count).sum(), List.copyOf(items));
    }

    private boolean allowed(Set<String> permissions, String code) {
        return permissions.contains("*:*:*") || permissions.contains(code);
    }

    private void add(List<Item> items, String type, String title, WorkReminderCount row) {
        if (row != null && row.getTotal() != null && row.getTotal() > 0) {
            items.add(new Item(type, title, row.getTotal(), row.getLatestAt()));
        }
    }
}
