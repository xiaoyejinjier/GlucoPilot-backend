package com.threadfit.glucopilot.community.message.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.message.dto.AdminMessageDtos.MessageItem;
import com.threadfit.glucopilot.community.message.mapper.AdminMessageMapper;
import com.threadfit.glucopilot.community.message.mapper.AdminMessageQuery;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMessageService {
    private final AdminMessageMapper mapper;

    public PageResult<MessageItem> messages(
            String keyword, Long senderId, Long recipientId, String readState, PageQuery page) {
        positive(senderId);
        positive(recipientId);
        String text = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (text != null && text.length() > 80) throw bad("关键词最多80字");
        String state = readState == null || readState.isBlank() ? null : readState.strip();
        if (state != null && !state.equals("READ") && !state.equals("UNREAD"))
            throw bad("已读状态应为READ或UNREAD");
        return query(new AdminMessageQuery(text, senderId, recipientId, state, null, null), page);
    }

    public PageResult<MessageItem> conversation(
            long firstUserId, long secondUserId, PageQuery page) {
        positive(firstUserId);
        positive(secondUserId);
        if (firstUserId == secondUserId) throw bad("请选择两个不同的用户");
        return query(
                new AdminMessageQuery(null, null, null, null, firstUserId, secondUserId), page);
    }

    private PageResult<MessageItem> query(AdminMessageQuery filters, PageQuery page) {
        if (page.getPageNum() < 1 || page.getPageSize() < 1 || page.getPageSize() > 100)
            throw bad("每页条数应为1～100，页码应大于0");
        long total = mapper.count(filters);
        if (total == 0) return PageResult.empty(page.getPageNum(), page.getPageSize());
        var records =
                mapper.page(filters, page.getPageSize(), page.offset()).stream()
                        .map(
                                m ->
                                        new MessageItem(
                                                m.getId().toString(),
                                                m.getSenderId().toString(),
                                                m.getSenderNickname(),
                                                m.getRecipientId().toString(),
                                                m.getRecipientNickname(),
                                                m.getContent(),
                                                m.getCreatedAt(),
                                                m.getReadAt()))
                        .toList();
        return PageResult.of(page.getPageNum(), page.getPageSize(), total, records);
    }

    private static void positive(Long value) {
        if (value != null && value < 1) throw bad("用户ID应为正整数");
    }

    private static BizException bad(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }
}
