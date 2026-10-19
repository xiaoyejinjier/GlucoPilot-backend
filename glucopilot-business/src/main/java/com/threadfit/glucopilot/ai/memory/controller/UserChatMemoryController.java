package com.threadfit.glucopilot.ai.memory.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.threadfit.glucopilot.ai.memory.dto.UserChatMemoryDtos;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;

/**
 * C 端"系统记住了我什么"（③ 期合规自助）：{@code /api/v1/users/me/ai-memories}。
 *
 * <h2>为什么这个接口必须存在</h2>
 * 长期记忆是"系统对用户的画像"，而且<b>跨会话生效</b>：用户在小程序里只能看到自己说过的话，
 * 看不到系统据此认定了什么，更看不到一条记错的偏好如何在之后每一轮回答里被引用。
 * 合规上"可解释 + 可删除"不能只给运营（4.14 / 4.15），必须有一条用户自己能走的路——
 * 否则删除权就成了"打电话找客服"。
 *
 * <h2>为什么挂在 /users/me 下，且不写 @PreAuthorize</h2>
 * <ul>
 *   <li>路径表达归属：{@code me} 由服务端从会话解析，客户端<b>无法</b>传别人的 user_id 进来——
 *       这比"传 id 再校验"少一个可能漏校验的地方；</li>
 *   <li>权限用的是 C 端会话鉴权（{@code /api/v1/**} 由 {@code SecurityConfig} 的 userChain 统一拦住），
 *       没有运营权限点可挂：{@code @PreAuthorize("@ss.hasPermi(...)")} 是运营侧那套，
 *       对 C 端用户会把「自己看自己的记忆」判成 403；</li>
 *   <li>身份只从 {@link RequestContext#requireUserId()} 取，<b>不</b>碰 {@code getOpsId()}：
 *       两者语义不同，混用会让审计字段落到错误的人身上。</li>
 * </ul>
 *
 * <h2>为什么删除是"全部"而不是按条删</h2>
 * 与管理端 4.15 同一理由：用户说"把你们记的关于我的东西删掉"时，他要的是"清空"，
 * 而不是在几条记忆里逐条挑；只删一部分还会让用户以为已经清空（见 {@code AdminChatMemoryController} 注释）。
 * 删除是<b>逻辑删除</b>：留痕是为了能解释"系统曾经记过什么"，但用户侧的读取路径已经查不到它们。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users/me/ai-memories")
@RequiredArgsConstructor
public class UserChatMemoryController {

    private final UserChatMemoryService userChatMemoryService;

    /** 查我自己的长期记忆（不返回任何内部字段，见 {@link UserChatMemoryDtos}）。 */
    @GetMapping
    public ApiResponse<UserChatMemoryDtos.MemoryList> list() {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(UserChatMemoryDtos.MemoryList.builder()
                .records(userChatMemoryService.listOwnForUser(userId))
                .build());
    }

    /**
     * 让系统彻底忘掉我。
     * <p>
     * 出参 {@code data = {deleted:N}}：前端据此区分"删掉了 N 条"与"本来就没有记忆"，
     * 两种都是成功（幂等），重复点击不会弹错。
     */
    @DeleteMapping
    public ApiResponse<UserChatMemoryDtos.DeleteResult> deleteAll() {
        Long userId = RequestContext.requireUserId();
        int deleted = userChatMemoryService.deleteAllForUser(userId);
        log.info("用户自助删除长期记忆 userId={} 条数={}", userId, deleted);
        return ApiResponse.ok(UserChatMemoryDtos.DeleteResult.builder().deleted(deleted).build());
    }
}
