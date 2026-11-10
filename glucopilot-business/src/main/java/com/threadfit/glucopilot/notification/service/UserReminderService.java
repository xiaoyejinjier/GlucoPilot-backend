package com.threadfit.glucopilot.notification.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.notification.dto.UserReminderDtos;
import com.threadfit.glucopilot.notification.entity.UserReminderRow;
import com.threadfit.glucopilot.notification.mapper.UserReminderMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserReminderService {
    private final UserReminderMapper mapper;
    private static final Set<String> SOCIAL = Set.of("LIKE", "COLLECT", "COMMENT", "FOLLOW");
    private static final Map<String, String> NAMES =
            Map.of(
                    "before_breakfast",
                    "早餐前",
                    "after_breakfast",
                    "早餐后",
                    "before_lunch",
                    "午餐前",
                    "after_lunch",
                    "午餐后",
                    "before_dinner",
                    "晚餐前",
                    "after_dinner",
                    "晚餐后");

    private void user(long userId) {
        if (mapper.countUser(userId) != 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
    }

    public UserReminderDtos.Result list(long userId, LocalDate date, PageQuery page) {
        user(userId);
        if (page.getPageNum() < 1 || page.getPageSize() < 1 || page.getPageSize() > 50) {
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
        }
        var alerts = mapper.alerts(userId, date).stream().map(row -> item("GLUCOSE", row)).toList();
        var replies =
                mapper.replies(userId, page.getPageSize(), page.offset()).stream()
                        .map(row -> item("REPLY", row))
                        .toList();
        return new UserReminderDtos.Result(
                date,
                alerts,
                PageResult.of(
                        page.getPageNum(),
                        page.getPageSize(),
                        mapper.countReplies(userId),
                        replies));
    }

    public UserReminderDtos.Item detail(long userId, String kind, long id) {
        user(userId);
        return item(kind, owned(userId, kind, id));
    }

    private UserReminderRow owned(long userId, String kind, long id) {
        if (id < 1 || !("GLUCOSE".equals(kind) || "REPLY".equals(kind))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提醒标识不正确");
        }
        var row = "GLUCOSE".equals(kind) ? mapper.alert(userId, id) : mapper.reply(userId, id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "提醒不存在或已更新");
        }
        return row;
    }

    /** 按已展示的内容保存快照；管理员/用户随后更新内容时，旧快照不会隐藏新提醒。 */
    @Transactional
    public void read(long userId, String kind, long id, UserReminderDtos.Read request) {
        user(userId);
        var row = owned(userId, kind, id);
        if (request == null || !revision(kind, row).equals(request.revision())) {
            throw new BizException(ErrorCode.CONFLICT, "提醒内容已更新，请刷新后查看");
        }
        mapper.saveRead(
                userId, kind, row, hash(row.getUserReply() == null ? "" : row.getUserReply()));
    }

    /** 只有用户主动点“全部已读”才调用，先固定边界再更新，避免吞掉更晚的新消息。 */
    @Transactional
    public void readInformation(long userId, String kind) {
        user(userId);
        if ("MESSAGE".equals(kind)) {
            Long through = mapper.messageCursor(userId);
            if (through != null) mapper.readMessages(userId, through);
        } else if (SOCIAL.contains(kind)) {
            Long through = mapper.noticeCursor(userId, kind);
            if (through != null) mapper.readNotices(userId, kind, through);
        } else if ("REPORT".equals(kind)) {
            var snapshots = mapper.reportSnapshots(userId);
            if (!snapshots.isEmpty()) mapper.readReports(userId, snapshots);
        } else {
            throw new BizException(ErrorCode.BAD_REQUEST, "提醒类别不正确");
        }
    }

    private static UserReminderDtos.Item item(String kind, UserReminderRow row) {
        return new UserReminderDtos.Item(
                kind,
                row.getSourceId().toString(),
                revision(kind, row),
                row.getRecordId().toString(),
                row.getRecordDate(),
                row.getNodeCode(),
                NAMES.getOrDefault(row.getNodeCode(), row.getNodeCode()),
                row.getGlucoseValue(),
                row.getRecordedAt(),
                row.getUserReply(),
                row.getRepliedAt(),
                row.getReadAt() != null);
    }

    /** 顶部一键已读：先固定事务快照，批量处理未展示的回复，任一写入失败一起回滚。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void readAll(long userId, LocalDate date) {
        user(userId);
        for (var row : mapper.alerts(userId, date)) {
            mapper.saveRead(userId, "GLUCOSE", row, hash(""));
        }
        while (true) {
            // 每次读取剩余未读的第一页；自己的已读写入在当前事务内立即可见。
            var replies = mapper.replies(userId, 50, 0);
            if (replies.isEmpty()) {
                break;
            }
            for (var row : replies) {
                mapper.saveRead(userId, "REPLY", row, hash(row.getUserReply()));
            }
        }
        for (String kind :
                new String[] {"MESSAGE", "LIKE", "COLLECT", "COMMENT", "FOLLOW", "REPORT"}) {
            readInformation(userId, kind);
        }
    }

    private static String revision(String kind, UserReminderRow row) {
        return hash(
                kind
                        + "|"
                        + row.getSourceId()
                        + "|"
                        + row.getGlucoseValue().stripTrailingZeros().toPlainString()
                        + "|"
                        + row.getRecordedAt()
                        + "|"
                        + row.getRepliedAt()
                        + "|"
                        + row.getUserReply());
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
