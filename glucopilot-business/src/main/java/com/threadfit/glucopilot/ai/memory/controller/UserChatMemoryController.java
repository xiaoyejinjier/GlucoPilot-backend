package com.threadfit.glucopilot.ai.memory.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.context.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <h2>删除的三级粒度</h2>
 * <ul>
 *   <li>{@code DELETE /} 清空全部：用户说"把你们记的关于我的东西删掉"时，他要的是这个；</li>
 *   <li>{@code DELETE /items/{id}} 删一个条目：用户只错记了一条（"我不吃香菜"其实是不吃葱）时用这个——
 *       这是 V71 之后最主要的路径，因为一条类下可以有多条；</li>
 *   <li>{@code DELETE /{memoryKey}} 清空本类：用户说"忌口别记了"时用这个，省得一条条点。</li>
 * </ul>
 * 三个入口都是<b>逻辑删除</b>：留痕是为了能解释"系统曾经记过什么"，但用户侧的读取路径已经查不到它们。
 * 删除之后由写入侧的条目级冷却（{@code ai.memory.deleted-key-cooldown-days}）挡住"自动抽取把它记回来"。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users/me/ai-memories")
@RequiredArgsConstructor
public class UserChatMemoryController {

    private final UserChatMemoryService userChatMemoryService;

    /**
     * 分页查我自己的长期记忆，可按类筛选。
     * <p>
     * 返回的对象带 {@code records} / {@code total} / {@code pages} 与五类的 {@code counts}：
     * 条目数不再有界，前端要能"上拉加载更多"；而记忆页顶部的分类入口要显示「各几条」，
     * 这份统计与"翻到第几页、正在筛哪一类"无关，所以随每次加载一起返回，页面不必多开一次请求。
     *
     * @param memoryKey 只看某一类（点开某个分类入口时传）；不传/空白表示全部
     */
    @GetMapping
    public ApiResponse<UserChatMemoryDtos.MemoryPage> list(@RequestParam(required = false) String memoryKey,
                                                           PageQuery query) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(userChatMemoryService.listOwnForUser(userId, memoryKey, query));
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

    /**
     * 删掉一个条目（记忆页每张卡片的「删除」）。
     * <p>
     * 幂等：本来就没有这条、或者这个 id 不属于当前用户，都返回 {@code deleted = 0} 的成功响应。
     * 不区分"不存在"与"不是你的"是刻意的——区分开就等于提供一个探测别人记忆 id 是否存在的接口。
     */
    @DeleteMapping("/items/{id}")
    public ApiResponse<UserChatMemoryDtos.DeleteResult> deleteItem(@PathVariable Long id) {
        Long userId = RequestContext.requireUserId();
        int deleted = userChatMemoryService.deleteByIdForUser(userId, id);
        log.info("用户自助删除单个长期记忆 userId={} id={} 条数={}", userId, id, deleted);
        return ApiResponse.ok(UserChatMemoryDtos.DeleteResult.builder().deleted(deleted).build());
    }

    /**
     * 清空某一类的全部条目（记忆页每类卡片上的「清空本类」）。
     * <p>
     * 幂等：这一类本来就没有生效条目也返回 {@code deleted = 0} 的成功响应。
     * 键不合法（不在五类白名单里）由服务层抛 400——让用户看到"类型不存在"，
     * 而不是一个"删除成功 0 条"的错觉。
     */
    @DeleteMapping("/{memoryKey}")
    public ApiResponse<UserChatMemoryDtos.DeleteResult> deleteByKey(@PathVariable String memoryKey) {
        Long userId = RequestContext.requireUserId();
        int deleted = userChatMemoryService.deleteByKeyForUser(userId, memoryKey);
        log.info("用户自助清空一类长期记忆 userId={} key={} 条数={}", userId, memoryKey, deleted);
        return ApiResponse.ok(UserChatMemoryDtos.DeleteResult.builder().deleted(deleted).build());
    }
}
