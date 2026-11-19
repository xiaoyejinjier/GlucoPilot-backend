package com.threadfit.glucopilot.consultation.service;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.entity.ConsultationConversation;
import com.threadfit.glucopilot.consultation.mapper.ConsultationConversationMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsultationConversationService {
    private final ConsultationConversationMapper mapper;

    public PageResult<ConsultationConversation> list(long user, PageQuery page) {
        if (page.getPageNum() < 1 || page.getPageSize() < 1 || page.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
        return PageResult.of(
                page.getPageNum(),
                page.getPageSize(),
                mapper.count(user),
                mapper.list(user, page.getPageSize(), page.offset()));
    }

    @Transactional
    public void read(long user, long id, long through) {
        if (id < 1 || through < 1) throw new BizException(ErrorCode.BAD_REQUEST, "提醒标识不正确");
        if (mapper.countIncoming(user, id, through) != 1)
            throw new BizException(ErrorCode.NOT_FOUND, "交流消息不存在或无权查看");
        mapper.read(user, id, through);
    }
}
