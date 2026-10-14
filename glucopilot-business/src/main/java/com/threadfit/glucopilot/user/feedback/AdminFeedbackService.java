package com.threadfit.glucopilot.user.feedback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.user.dto.AdminFeedbackDtos;
import com.threadfit.glucopilot.user.entity.Feedback;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.FeedbackMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 运营端反馈列表、状态流转与官方回复。 */
@Service
@RequiredArgsConstructor
public class AdminFeedbackService {

    private static final int HANDLED = 2;

    private final FeedbackMapper feedbackMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserAccountMapper userAccountMapper;

    public PageResult<AdminFeedbackDtos.FeedbackItem> page(
            Integer feedbackType, Integer replyStatus, String keyword, PageQuery query) {
        Page<Feedback> rows = feedbackMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<Feedback>()
                        .eq(feedbackType != null, Feedback::getFeedbackType, feedbackType)
                        .eq(replyStatus != null, Feedback::getReplyStatus, replyStatus)
                        .like(StringUtils.hasText(keyword), Feedback::getContentText,
                                keyword == null ? null : keyword.trim())
                        .orderByAsc(Feedback::getId));

        List<Long> userIds = rows.getRecords().stream()
                .map(Feedback::getUserId)
                .distinct()
                .toList();
        Map<Long, UserProfile> profiles = userIds.isEmpty()
                ? Collections.emptyMap()
                : userProfileMapper.selectList(new QueryWrapper<UserProfile>()
                        .in("user_id", userIds)).stream()
                        .collect(Collectors.toMap(UserProfile::getUserId, Function.identity(), (a, b) -> a));
        Map<Long, UserAccount> accounts = userIds.isEmpty()
                ? Collections.emptyMap()
                : userAccountMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));

        List<AdminFeedbackDtos.FeedbackItem> items = rows.getRecords().stream()
                .map(row -> toItem(row, profiles.get(row.getUserId()), accounts.get(row.getUserId())))
                .toList();
        return PageResult.of(rows.getCurrent(), rows.getSize(), rows.getTotal(), items);
    }

    @Transactional
    public AdminFeedbackDtos.ReplyResult reply(Long opsId, Long feedbackId, AdminFeedbackDtos.ReplyRequest request) {
        CodeEnum.of(CodeEnum.ReplyStatus.class, request.getReplyStatus());
        String reply = StringUtils.hasText(request.getOfficialReply())
                ? request.getOfficialReply().trim()
                : null;
        if (request.getReplyStatus() == HANDLED && reply == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标记已处理时必须填写官方回复");
        }

        Feedback row = feedbackMapper.selectById(feedbackId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "反馈不存在");
        }
        row.setReplyStatus(request.getReplyStatus());
        row.setOfficialReply(reply);
        if (reply == null) {
            row.setRepliedBy(null);
            row.setRepliedAt(null);
        } else {
            row.setRepliedBy(opsId);
            row.setRepliedAt(LocalDateTime.now());
        }
        feedbackMapper.updateById(row);
        return AdminFeedbackDtos.ReplyResult.builder().updated(true).build();
    }

    private static AdminFeedbackDtos.FeedbackItem toItem(
            Feedback row, UserProfile profile, UserAccount account) {
        String fallbackName = account != null && StringUtils.hasText(account.getUsername())
                ? account.getUsername()
                : "用户#" + row.getUserId();
        String userName = profile != null && StringUtils.hasText(profile.getNickname())
                ? profile.getNickname()
                : fallbackName;
        return AdminFeedbackDtos.FeedbackItem.builder()
                .id(String.valueOf(row.getId()))
                .userId(String.valueOf(row.getUserId()))
                .userName(userName)
                .contact(account == null ? null : PhoneUtils.mask(account.getPhone()))
                .feedbackType(row.getFeedbackType())
                .contentText(row.getContentText())
                .replyStatus(row.getReplyStatus())
                .officialReply(row.getOfficialReply())
                .createdAt(row.getGmtCreate())
                .repliedAt(row.getRepliedAt())
                .build();
    }
}
