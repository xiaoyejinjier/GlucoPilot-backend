package com.threadfit.glucopilot.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

/** Read-only work queue summary; permissions are the current operator's server-side grants. */
@Service
@RequiredArgsConstructor
public class AdminWorkReminderService {
    private final JdbcTemplate jdbc;
    public record Item(String type,String title,long count,LocalDateTime latestAt) {}
    public record Summary(long total,List<Item> items) {}

    public Summary summary(Set<String> permissions) {
        var items=new ArrayList<Item>();
        if(allowed(permissions,"content:news:list")) add(items,"REPORT","新举报",
                "SELECT COUNT(*) total,MAX(created_at) latest_at FROM community_report WHERE state='OPEN'");
        if(allowed(permissions,"feedback:manage")) add(items,"FEEDBACK","新反馈",
                "SELECT COUNT(*) total,MAX(gmt_create) latest_at FROM feedback WHERE reply_status=0 AND is_deleted=0");
        items.sort(Comparator.comparing(Item::latestAt,Comparator.nullsLast(Comparator.reverseOrder())));
        return new Summary(items.stream().mapToLong(Item::count).sum(),List.copyOf(items));
    }
    private boolean allowed(Set<String> permissions,String code) {
        return permissions.contains("*:*:*")||permissions.contains(code);
    }
    private void add(List<Item> items,String type,String title,String sql) {
        var item=jdbc.queryForObject(sql,(rs,row)->new Item(type,title,rs.getLong("total"),
                rs.getTimestamp("latest_at")==null?null:rs.getTimestamp("latest_at").toLocalDateTime()));
        if(item!=null&&item.count()>0)items.add(item);
    }
}
