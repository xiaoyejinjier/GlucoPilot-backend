package com.threadfit.glucopilot.user.feedback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.user.dto.FeedbackDtos;
import com.threadfit.glucopilot.user.entity.Feedback;
import com.threadfit.glucopilot.user.mapper.FeedbackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户反馈留言中心：提交与「我的反馈」查询。
 * 官方回复只读展示，写入属于运营端。
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private static final int RECEIVED = 0;

    private final FeedbackMapper feedbackMapper;

    @Transactional
    public FeedbackDtos.SubmitResult submit(Long userId, FeedbackDtos.SubmitRequest request) {
        CodeEnum.of(CodeEnum.FeedbackType.class, request.getFeedbackType());

        Feedback row = new Feedback();
        row.setUserId(userId);
        row.setFeedbackType(request.getFeedbackType());
        row.setContentText(request.getContentText().trim());
        row.setReplyStatus(RECEIVED);
        feedbackMapper.insert(row);

        return FeedbackDtos.SubmitResult.builder()
                .id(String.valueOf(row.getId()))
                .replyStatus(row.getReplyStatus())
                .build();
    }

    public PageResult<FeedbackDtos.FeedbackItem> page(Long userId, PageQuery query) {
        Page<Feedback> page = feedbackMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<Feedback>()
                        .eq(Feedback::getUserId, userId)
                        .orderByDesc(Feedback::getGmtCreate)
                        .orderByDesc(Feedback::getId));
        Page<FeedbackDtos.FeedbackItem> mapped = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        mapped.setRecords(page.getRecords().stream().map(FeedbackService::toItem).toList());
        return PageResult.of(mapped);
    }

    private static FeedbackDtos.FeedbackItem toItem(Feedback f) {
        return FeedbackDtos.FeedbackItem.builder()
                .id(String.valueOf(f.getId()))
                .feedbackType(f.getFeedbackType())
                .contentText(f.getContentText())
                .replyStatus(f.getReplyStatus())
                .officialReply(f.getOfficialReply())
                .repliedAt(f.getRepliedAt())
                .createdAt(f.getGmtCreate())
                .build();
    }
}
