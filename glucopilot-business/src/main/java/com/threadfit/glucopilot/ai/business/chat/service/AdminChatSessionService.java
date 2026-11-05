package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.AdminChatSessionDtos;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;

/**
 * 管理端「AI 会话审计」读取服务（只读）。
 *
 * <h2>职责边界</h2>
 * 只做读取与投影：不改会话状态、不发消息、不重建上下文。它能回答三个问题——
 * 「哪些会话值得看」（列表）、「这一轮聊了什么」（消息预览）、「这一条到底写了什么」（单条全文）。
 *
 * <h2>为什么全文要单独一个方法</h2>
 * 全文是用户隐私里最敏感的一层。把它与列表分开，是为了让「读全文」在代码与审计两个层面
 * 都是一次显式动作：{@link #messageDetail(long, long)} 会写 {@code ops_audit_log}，
 * 而列表与预览不写（否则运营翻一页就产生几十条审计，噪音盖住真正的越权信号）。
 */
public interface AdminChatSessionService {

    /** 会话列表：按最近活动倒序，带「有没有摘要」与「有几轮分片」。 */
    PageResult<AdminChatSessionDtos.SessionRow> list(Long userId, Integer status, String keyword, PageQuery query);

    /** 某会话的消息（倒序，{@code beforeSeq} 往前翻），每条只给 ≤200 字预览。 */
    PageResult<AdminChatSessionDtos.MessageRow> messages(long sessionId, Integer beforeSeq, PageQuery query);

    /** 单条消息全文；调用会留下一条运营审计。 */
    AdminChatSessionDtos.MessageDetail messageDetail(long sessionId, long messageId);
}
