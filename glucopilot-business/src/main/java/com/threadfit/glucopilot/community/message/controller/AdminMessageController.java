package com.threadfit.glucopilot.community.message.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.community.message.dto.AdminMessageDtos.MessageItem;
import com.threadfit.glucopilot.community.message.service.AdminMessageService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/community/messages")
@RequiredArgsConstructor
@PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('user:manage')")
public class AdminMessageController {
    private final AdminMessageService service;

    @GetMapping
    public ApiResponse<PageResult<MessageItem>> messages(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long senderId,
            @RequestParam(required = false) Long recipientId,
            @RequestParam(required = false) String readState,
            @Valid PageQuery page) {
        return ApiResponse.ok(service.messages(keyword, senderId, recipientId, readState, page));
    }

    @GetMapping("/conversation/{firstUserId}/{secondUserId}")
    public ApiResponse<PageResult<MessageItem>> conversation(
            @PathVariable long firstUserId,
            @PathVariable long secondUserId,
            @Valid PageQuery page) {
        return ApiResponse.ok(service.conversation(firstUserId, secondUserId, page));
    }
}
