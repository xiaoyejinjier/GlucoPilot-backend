package com.threadfit.glucopilot.notification.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.notification.entity.WorkReminderSource;
import com.threadfit.glucopilot.notification.mapper.AdminWorkReminderMapper;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** 待办处理状态与当前管理员的已读状态分开保存。 */
@Service
@RequiredArgsConstructor
public class AdminWorkReminderService {
    private final AdminWorkReminderMapper mapper;

    private static final Map<String, String> PERMISSIONS =
            Map.of(
                    "REPORT",
                    "content:news:list",
                    "FEEDBACK",
                    "feedback:manage",
                    "FOOD_REVIEW",
                    "food:library:manage",
                    "GLUCOSE_UPDATE",
                    "health:alert:view");
    private static final Map<String, String> TITLES =
            Map.of(
                    "REPORT",
                    "新举报",
                    "FEEDBACK",
                    "新反馈",
                    "FOOD_REVIEW",
                    "待审核食品",
                    "GLUCOSE_UPDATE",
                    "血糖记录已更新");

    public record Item(
            String type,
            String title,
            long count,
            LocalDateTime latestAt,
            long pendingCount,
            String revision,
            String recordId) {}

    public record Summary(long total, List<Item> items) {}

    public record ReadRequest(@NotEmpty @Size(max = 4) Map<String, String> revisions) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary(long opsId, Set<String> permissions) {
        var items = new ArrayList<Item>();
        for (String kind : List.of("REPORT", "FEEDBACK", "FOOD_REVIEW", "GLUCOSE_UPDATE")) {
            if (!allowed(permissions, kind)) continue;
            var sources = mapper.sources(opsId, kind);
            if (sources.isEmpty()) continue;
            long unread = sources.stream().filter(row -> row.getReadAt() == null).count();
            var latest =
                    sources.stream()
                            .map(WorkReminderSource::getLatestAt)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null);
            var target =
                    sources.stream()
                            .filter(row -> row.getReadAt() == null)
                            .findFirst()
                            .orElse(sources.getFirst());
            items.add(
                    new Item(
                            kind,
                            TITLES.get(kind),
                            unread,
                            latest,
                            sources.size(),
                            revision(sources),
                            kind.equals("GLUCOSE_UPDATE") ? target.getId().toString() : null));
        }
        items.sort(
                Comparator.comparing(
                        Item::latestAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Summary(items.stream().mapToLong(Item::count).sum(), List.copyOf(items));
    }

    /** 核对已展示集合后再写回执；新待办及随后修改的测量保持未读。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void read(long opsId, Set<String> permissions, ReadRequest request) {
        if (request == null
                || request.revisions() == null
                || request.revisions().isEmpty()
                || request.revisions().size() > 4) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择需要标记的消息");
        }
        var snapshots = new LinkedHashMap<String, List<WorkReminderSource>>();
        for (var entry : request.revisions().entrySet()) {
            String kind = entry.getKey();
            if (kind == null || !PERMISSIONS.containsKey(kind))
                throw new BizException(ErrorCode.BAD_REQUEST, "消息类别不正确");
            if (!allowed(permissions, kind)) throw new BizException(ErrorCode.FORBIDDEN);
            if (entry.getValue() == null || !entry.getValue().matches("[a-f0-9]{64}")) {
                throw new BizException(ErrorCode.BAD_REQUEST, "消息快照不正确");
            }
            var sources = mapper.sources(opsId, kind);
            if (!revision(sources).equals(entry.getValue())) {
                throw new BizException(ErrorCode.CONFLICT, "消息已更新，请刷新后标记已读");
            }
            snapshots.put(kind, sources.stream().filter(row -> row.getReadAt() == null).toList());
        }
        for (var entry : snapshots.entrySet()) {
            var rows = entry.getValue();
            for (int start = 0; start < rows.size(); start += 500) {
                mapper.saveReads(
                        opsId,
                        entry.getKey(),
                        rows.subList(start, Math.min(start + 500, rows.size())));
            }
        }
    }

    private boolean allowed(Set<String> permissions, String kind) {
        return permissions.contains("*:*:*") || permissions.contains(PERMISSIONS.get(kind));
    }

    private String revision(List<WorkReminderSource> sources) {
        StringBuilder value = new StringBuilder();
        sources.stream()
                .sorted(Comparator.comparing(WorkReminderSource::getId))
                .forEach(
                        row ->
                                value.append(row.getId())
                                        .append(':')
                                        .append(row.getRevision())
                                        .append('\n'));
        return UserReminderService.hash(value.toString());
    }
}
