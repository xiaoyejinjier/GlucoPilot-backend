package com.threadfit.glucopilot.ai.business.chat.controller;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatContextDtos;
import com.threadfit.glucopilot.ai.business.chat.service.ChatContextQueryService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分片审计的只读入口（管理端排障）。
 *
 * <h2>为什么挂在 ai 模块而不是业务会话接口下</h2>
 * {@code chat_context} 是"装配期估算"的产物，回答的是「提示词/记忆/摘要/工具摘要各自占了多少预算」
 * 这类 AI 侧问题；C 端会话接口只暴露用户能理解的东西（消息、标题、置顶）。把审计塞进 C 端会让
 * 用户看到一个"token 预算"字段却无法行动。
 *
 * <h2>为什么不提供写/删</h2>
 * 分片与业务数据同生命周期，删会话时该不该跟着删仍然是产品决定；在它变成决定之前不给"顺手删掉"
 * 的入口（账号注销的物理删除走 {@code ChatContextMapper.purgeByUserId}，是另一条合规路径）。
 *
 * <h2>权限为什么是 ai:chat:audit 而不是 ai:model:query</h2>
 * 分片回答的是「这一轮的提示词/记忆/摘要各占多少预算」，它描述的是<b>某次真实对话</b>；
 * 配合会话 ID 可以定位到具体用户。{@code ai:model:query} 的语义是"能看模型配置"，不该顺带获得
 * 读对话的能力，因此拆出 {@code ai:chat:audit}（V53）。
 */
@RestController
@RequestMapping("/admin/v1/ai/chat-sessions")
@RequiredArgsConstructor
public class AdminChatContextController {

    private final ChatContextQueryService queryService;

    /** 查某个会话的分片审计；{@code round} 可选，缺省返回全部轮次。 */
    @GetMapping("/{sessionId}/context")
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<ChatContextDtos.ContextView> context(@PathVariable Long sessionId,
                                                            @RequestParam(required = false) Integer round) {
        return ApiResponse.ok(queryService.view(sessionId, round));
    }
}
