package com.threadfit.glucopilot.ai.business.chat.controller;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatSmokeDtos;
import com.threadfit.glucopilot.ai.business.chat.service.ChatSmokeService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L0 冒烟端点：验证大模型链路是否打通（同步、不落库、非流式）。
 * <p>
 * 需要登录态（与 /api/v1/** 其余接口一致）。
 * TODO L1 三个正式接口（2.23 / 2.24 / 2.25）验收通过后删除本类。
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatSmokeController {

    private final ChatSmokeService chatSmokeService;

    @PostMapping("/smoke")
    public ApiResponse<ChatSmokeDtos.SmokeResponse> smoke(@Valid @RequestBody ChatSmokeDtos.SmokeRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(chatSmokeService.smoke(userId, request.getMessage()));
    }
}
