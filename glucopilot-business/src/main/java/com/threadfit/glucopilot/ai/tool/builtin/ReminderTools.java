package com.threadfit.glucopilot.ai.tool.builtin;

import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.notification.dto.UserReminderDtos;
import com.threadfit.glucopilot.notification.service.UserReminderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 今日提醒工具（{@code list_reminders}）。
 *
 * <p>数据来自用户的消息中心（{@code UserReminderService}），它每天按「该记录但还没记录的时段」
 * 生成提醒，并带上用户对提醒的回复。因此这个工具回答的是
 * <b>「我今天还差什么、我之前回了什么」</b>，而不是「我设过哪些闹钟」——
 * 目前系统里没有独立的「提醒计划」实体，别让模型误以为有。
 *
 * <p>只回摘要（时段名 + 是否有值 + 是否已读）：提醒条数一天可能有九条，
 * 全量返回会把上下文占满，而模型真正需要的是"还差几个时段"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTools {

    /** 最多返回几条提醒明细。 */
    private static final int ALERT_LIMIT = 6;

    /** 最多返回几条回复明细。 */
    private static final int REPLY_LIMIT = 3;

    private final UserReminderService userReminderService;

    private final JsonUtils jsonUtils;

    @Tool(
            name = "list_reminders",
            description =
                    "查询当前用户今天的提醒与待记录项：今天还有哪些时段没记录、有没有需要回的提醒，以及最近几条已回复的内容。"
                            + "用户问「我今天还差什么」「有什么提醒」「我回过的那个呢」时使用。只会读出今天的，不支持查历史某一天。")
    public String listReminders(
            @ToolParam(required = false, description = "是否同时返回最近几条已回复的提醒，默认 false") Boolean includeReplies,
            ToolContext toolContext) {

        Long userId = AiToolContext.requireUserId(toolContext);
        LocalDate today = LocalDate.now();

        PageQuery page = new PageQuery();
        page.setPageSize(Boolean.TRUE.equals(includeReplies) ? REPLY_LIMIT : 1);

        UserReminderDtos.Result result;
        try {
            result = userReminderService.list(userId, today, page);
        } catch (RuntimeException e) {
            log.error("工具 list_reminders 查询失败 userId={} date={}", userId, today, e);
            return error("查询今日提醒时出错，请稍后重试");
        }

        List<UserReminderDtos.Item> alerts = result == null || result.alerts() == null ? List.of() : result.alerts();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", today.toString());
        payload.put("alertCount", alerts.size());
        payload.put("alerts", alerts(alerts));

        if (Boolean.TRUE.equals(includeReplies)) {
            var replies = result == null ? null : result.replies();
            List<UserReminderDtos.Item> replyItems =
                    replies == null || replies.getRecords() == null ? List.of() : replies.getRecords();
            payload.put("replyTotal", replies == null ? 0 : replies.getTotal());
            payload.put("replies", replies(replyItems));
        }

        payload.put(
                "hint",
                alerts.isEmpty()
                        ? "今天没有待记录的提醒。若用户说他刚记过，说明记录已经同步；不要凭空编造缺失项。"
                        : "alertCount 是今天待记录项的总数，alerts 只列了前 " + ALERT_LIMIT + " 条。"
                                + "timeout 的时段如果用户想现在补记，告诉他去「记录」页填写，本工具不能写入。");
        log.info(
                "工具 list_reminders 执行 userId={} date={} 提醒 {} 条",
                userId, today, alerts.size());
        return jsonUtils.toJson(payload);
    }

    private static List<Map<String, Object>> alerts(List<UserReminderDtos.Item> items) {
        List<Map<String, Object>> list = new ArrayList<>(Math.min(items.size(), ALERT_LIMIT));
        for (UserReminderDtos.Item item : items) {
            if (list.size() >= ALERT_LIMIT) {
                break;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", item.kind());
            map.put("nodeName", item.nodeName());
            map.put("recordDate", item.recordDate() == null ? null : item.recordDate().toString());
            map.put("hasValue", item.glucoseValue() != null);
            map.put("read", item.read());
            if (item.userReply() != null && !item.userReply().isBlank()) {
                map.put("userReply", item.userReply());
            }
            list.add(map);
        }
        return list;
    }

    private static List<Map<String, Object>> replies(List<UserReminderDtos.Item> items) {
        List<Map<String, Object>> list = new ArrayList<>(Math.min(items.size(), REPLY_LIMIT));
        for (UserReminderDtos.Item item : items) {
            if (list.size() >= REPLY_LIMIT) {
                break;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("nodeName", item.nodeName());
            map.put(
                    "recordedAt",
                    item.recordedAt() == null ? null : item.recordedAt().toString());
            map.put("reply", item.userReply());
            map.put(
                    "repliedAt",
                    item.repliedAt() == null ? null : item.repliedAt().toString());
            list.add(map);
        }
        return list;
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
