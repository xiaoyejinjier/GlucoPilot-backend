package com.threadfit.glucopilot.community.message.controller;

import static com.threadfit.glucopilot.community.message.dto.MessageDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.message.service.CommunityMessageService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommunityMessageController {
    private final CommunityMessageService service;

    private long user() {
        return RequestContext.requireUserId();
    }

    @GetMapping("/community/messages")
    public ApiResponse<PageResult<Conversation>> inbox(@Valid PageQuery q) {
        return ApiResponse.ok(service.inbox(user(), q));
    }

    @GetMapping("/community/messages/unread")
    public ApiResponse<Unread> unread() {
        return ApiResponse.ok(service.unread(user()));
    }

    @GetMapping("/community/messages/{peerUserId}")
    public ApiResponse<Messages> messages(
            @PathVariable long peerUserId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.messages(user(), peerUserId, beforeId, pageSize));
    }

    @PostMapping("/community/messages/{peerUserId}")
    public ApiResponse<Message> send(
            @PathVariable long peerUserId, @Valid @RequestBody MessageRequest request) {
        return ApiResponse.ok(service.send(user(), peerUserId, request));
    }

    @PutMapping("/community/messages/{peerUserId}/read")
    public ApiResponse<Void> read(
            @PathVariable long peerUserId, @Valid @RequestBody ReadRequest request) {
        service.read(user(), peerUserId, request.throughId());
        return ApiResponse.ok();
    }
}
