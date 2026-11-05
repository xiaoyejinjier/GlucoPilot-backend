package com.threadfit.glucopilot.ai.business.chat.controller;

import com.threadfit.glucopilot.ai.business.chat.dto.AdminChatSessionDtos;
import com.threadfit.glucopilot.ai.business.chat.service.AdminChatSessionService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「AI 会话审计」（只读）。
 *
 * <h2>权限</h2>
 * 统一用 {@code ai:chat:audit}（V53 从 {@code ai:model:query} 拆出）：这一组接口会返回
 * <b>用户原始对话</b>，与「能配模型」「能看用量汇总」不是同一级别的授权，默认只给超管
 * （V53 未把它授予任何角色，超管靠 {@code *:*:*}）。
 *
 * <h2>三个接口的分工</h2>
 * <ul>
 *   <li>{@code GET /} 会话列表：只有元数据，用来挑「看哪个」；</li>
 *   <li>{@code GET /{id}/messages} 消息预览：≤200 字，用来挑「看哪条」；</li>
 *   <li>{@code GET /{id}/messages/{msgId}} 单条全文：<b>会写审计</b>，是真正的取证动作。</li>
 * </ul>
 * 拆成三级而不是一个「详情」接口，是为了让审计只记「读到了正文」这件事，
 * 而不至于运营翻一页就产生几十条噪音记录。
 *
 * <h2>为什么没有写接口</h2>
 * 审计页不提供删除、导出、改状态：会话与消息的删除权属于用户（账号注销/删除会话），
 * 运营侧一旦有了删除入口，就等于给「抹掉证据」开了一个口子。
 */
@RestController
@RequestMapping("/admin/v1/ai/chat-sessions")
@RequiredArgsConstructor
public class AdminChatSessionController {

    private final AdminChatSessionService service;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<AdminChatSessionDtos.SessionRow>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery query) {
        return ApiResponse.ok(service.list(userId, status, keyword, query));
    }

    @GetMapping("/{sessionId}/messages")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<AdminChatSessionDtos.MessageRow>> messages(
            @PathVariable long sessionId,
            @RequestParam(required = false) Integer beforeSeq,
            @Valid PageQuery query) {
        return ApiResponse.ok(service.messages(sessionId, beforeSeq, query));
    }

    @GetMapping("/{sessionId}/messages/{messageId}")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<AdminChatSessionDtos.MessageDetail> messageDetail(
            @PathVariable long sessionId,
            @PathVariable long messageId) {
        return ApiResponse.ok(service.messageDetail(sessionId, messageId));
    }
}
