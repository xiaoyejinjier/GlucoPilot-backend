package com.threadfit.glucopilot.ai.memory;

import com.threadfit.glucopilot.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端"用户 AI 长期记忆"（契约 4.14 查询 / 4.15 删除）。
 *
 * <h2>为什么挂在 /users/{id} 下</h2>
 * 两个接口都是对"某个用户的记忆"的操作，归属用户是它们的必要上下文。旧版契约文档把 4.15 写成
 * {@code DELETE /admin/v1/chat-memories/{id}}（按记忆 ID 删），本实现按 {@code /users/{id}/chat-memories}
 * 落地，原因是运营侧的真实诉求是"这个人要求我们忘掉他"——一次删掉该用户全部记忆，
 * 而不是在几十条记忆里逐条挑；按记忆 ID 单删的形式保留了"只删一部分"，反而会让用户以为
 * 自己已被清空而系统其实还留着几条。语义变化因此落在路径上，前端不需要猜。
 *
 * <h2>权限与日志</h2>
 * <ul>
 *   <li>读要 {@code ai:memory:view}，删要 {@code ai:memory:manage}——看与删分开，
 *       因为"能看用户健康记忆"不该顺带等于"能替用户删记忆"；</li>
 *   <li>删除接口<b>刻意不加 {@code @Log}</b>：RuoYi 的操作日志会把入参落进 {@code sys_oper_log}，
 *       而这里的语义是"删除"，日志里却会留下"曾经有过哪些记忆"的痕迹（与合规删除的初衷相反）。
 *       留痕由 {@code ops_audit_log} 承担（只记条数与操作人，见 {@link AdminChatMemoryService}）。
 *       这与 {@code AdminAiModelController} 不加 {@code @Log} 的理由一致：避免内容/密钥进入另一张日志表。</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/v1/users/{id}/chat-memories")
@RequiredArgsConstructor
public class AdminChatMemoryController {

    private final AdminChatMemoryService adminChatMemoryService;

    /** 4.14：只读查询该用户的跨会话长期记忆。 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:memory:view')")
    public ApiResponse<AdminChatMemoryDtos.MemoryList> list(
            @PathVariable Long id,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(adminChatMemoryService.list(id, pageNum, pageSize));
    }

    /** 4.15：逻辑删除该用户全部长期记忆；出参 data 为 null（前端只看成功与否）。 */
    @DeleteMapping
    @PreAuthorize("@ss.hasPermi('ai:memory:manage')")
    public ApiResponse<Void> deleteAll(@PathVariable Long id) {
        adminChatMemoryService.deleteAll(id);
        return ApiResponse.ok();
    }
}
